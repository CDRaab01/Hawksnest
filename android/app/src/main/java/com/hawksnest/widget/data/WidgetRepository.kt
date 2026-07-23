package com.hawksnest.widget.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.WIDGET_CONFIRM_WINDOW_MS
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.predictLight
import com.hawksnest.core.logic.resolveName
import com.hawksnest.core.logic.toSnapshot
import com.hawksnest.di.ApplicationScope
import com.hawksnest.widget.glanceWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a widget does that isn't drawing.
 *
 * Actions arrive on a broadcast with roughly ten seconds of guaranteed execution, which is not
 * enough for a thirty-second echo wait. So the fast part (mark pending, send the command) happens
 * inline and the slow part is handed to the application scope — the same scope the HA socket runs
 * in, which outlives the callback. If the process dies mid-wait the pending marker is left behind
 * in storage; `widgetPending` expires it on the next render so nothing spins forever.
 */
@Singleton
class WidgetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: WidgetHaClient,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {
    // Not constructor-injected: this class is bound to Context and Glance, so it is exercised on
    // device, not in unit tests. The seams worth faking (the poll clock, the REST calls) are
    // faked in WidgetEcho and WidgetHaClient, which are tested directly.
    private val echo = WidgetEcho()
    private val nowMs: () -> Long = System::currentTimeMillis

    /**
     * When each widget last hit the network, in this process.
     *
     * This exists to break a loop, not to save bytes. Writing a widget's state redraws it, a
     * redraw re-runs `provideGlance`, and `provideGlance` asks for a refresh — so a refresh that
     * always fetched would feed itself forever, at whatever rate the network allowed. In-memory
     * is the right lifetime: a cold process has never fetched and should, and a live one has
     * just fetched and shouldn't.
     */
    private val lastFetchAt = ConcurrentHashMap<GlanceId, Long>()

    /**
     * Re-read this widget's entity without blocking the caller — what `provideGlance` calls on
     * every render. Throttled by [lastFetchAt]; a render moments after a fetch is a redraw of
     * that fetch, not a reason for another one.
     */
    fun refreshAsync(kind: WidgetKind, glanceId: GlanceId) {
        val now = nowMs()
        val last = lastFetchAt[glanceId]
        if (last != null && (now - last) in 0 until MIN_RENDER_REFRESH_MS) return
        lastFetchAt[glanceId] = now
        scope.launch { refresh(kind, glanceId) }
    }

    suspend fun refresh(kind: WidgetKind, glanceId: GlanceId) {
        val entityId = prefs(glanceId).entityId()
        if (entityId == null) {
            write(kind, glanceId) { it.putBlocker(WidgetBlocker.NOT_CONFIGURED) }
            return
        }
        fetch(kind, glanceId, entityId)
    }

    /** Point a freshly-created widget at an entity, then draw it. */
    suspend fun configure(kind: WidgetKind, glanceId: GlanceId, entityId: String, name: String) {
        write(kind, glanceId) { prefs ->
            prefs.clear()
            prefs[WidgetKeys.ENTITY_ID] = entityId
            prefs[WidgetKeys.NAME] = name
        }
        refresh(kind, glanceId)
    }

    /**
     * Arm a "tap again to confirm" for a destructive command (unlock, disarm) and let it lapse on
     * its own, so a widget left armed on the home screen doesn't stay one stray tap from an open
     * door. A newer arm supersedes this one rather than being cancelled by its expiry.
     */
    fun armConfirmAsync(kind: WidgetKind, glanceId: GlanceId, service: String) {
        scope.launch {
            val armedAt = nowMs()
            write(kind, glanceId) { prefs ->
                prefs[WidgetKeys.CONFIRM_SINCE] = armedAt
                prefs[WidgetKeys.CONFIRM_SERVICE] = service
            }
            delay(WIDGET_CONFIRM_WINDOW_MS + LAPSE_GRACE_MS)
            if (prefs(glanceId).confirmSince() == armedAt) {
                write(kind, glanceId) { it.clearConfirm() }
            }
        }
    }

    fun actAsync(
        kind: WidgetKind,
        glanceId: GlanceId,
        service: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch { act(kind, glanceId, service, extra) }
    }

    suspend fun act(
        kind: WidgetKind,
        glanceId: GlanceId,
        service: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val current = prefs(glanceId)
        val entityId = current.entityId()
        if (entityId == null) {
            write(kind, glanceId) { it.putBlocker(WidgetBlocker.NOT_CONFIGURED) }
            return
        }
        val snapshot = current.snapshot(json)
        val before = snapshot?.state
        val startedAt = nowMs()

        write(kind, glanceId) { prefs ->
            prefs.clearConfirm()
            if (kind == WidgetKind.LIGHT && snapshot != null) {
                // Draw the result now; the confirming read below corrects it if HA disagrees.
                prefs.putSnapshot(predictLight(snapshot, service, extra, startedAt), json)
            } else {
                prefs[WidgetKeys.PENDING_SINCE] = startedAt
            }
        }

        // The entity's own domain, not the widget's: a "light" widget may be pointed at a
        // switch entity, and `switch.turn_on` is a different service from `light.turn_on`.
        val sent = client.callService(domainOf(entityId), service, entityId, extra)
        if (sent is HaCall.Failed) {
            write(kind, glanceId) { prefs ->
                prefs.clearPending()
                prefs.putBlocker(sent.blocker)
                if (kind != WidgetKind.LIGHT) prefs.maskState()
            }
            return
        }

        if (kind == WidgetKind.LIGHT) {
            // HA applies a light immediately; one read a beat later reconciles the optimistic draw.
            delay(LIGHT_CONFIRM_DELAY_MS)
            fetch(kind, glanceId, entityId)
            return
        }

        val outcome = echo.awaitSettled(kind, before) {
            (client.state(entityId) as? HaCall.Ok)?.value?.state
        }
        when (outcome) {
            is EchoOutcome.Settled -> {
                fetch(kind, glanceId, entityId)
                write(kind, glanceId) { it.clearPending() }
            }
            is EchoOutcome.GaveUp -> {
                // Nothing settled in thirty seconds. Say so, and don't leave a guess on screen.
                write(kind, glanceId) { prefs ->
                    prefs.clearPending()
                    prefs.maskState()
                    prefs.putBlocker(WidgetBlocker.NO_RESPONSE)
                }
            }
        }
    }

    /**
     * Store a reading that arrived from the app's live socket rather than from a widget's own
     * fetch (see `WidgetLiveBridge`). A push counts as a fresh read — it came from the same HA
     * over a connection we know is up — so it satisfies the security freshness window too.
     */
    suspend fun publish(kind: WidgetKind, glanceId: GlanceId, entity: HassEntity) {
        val name = resolveName(entity, overrides)
        lastFetchAt[glanceId] = nowMs()
        write(kind, glanceId) { it.putSnapshot(entity.toSnapshot(name, nowMs()), json) }
    }

    /** The reading a widget currently holds, if any. */
    suspend fun storedState(glanceId: GlanceId): String? = prefs(glanceId).snapshot(json)?.state

    /** The entity a widget is configured for, or null when it was never set up. */
    suspend fun storedEntityId(glanceId: GlanceId): String? = prefs(glanceId).entityId()

    /** Read [entityId] and store it. Returns the new state, or null when the fetch failed. */
    private suspend fun fetch(kind: WidgetKind, glanceId: GlanceId, entityId: String): String? {
        // Counted whatever the outcome: a failing fetch must throttle the next render's attempt
        // exactly as a succeeding one does, or an unreachable HA becomes a retry storm.
        lastFetchAt[glanceId] = nowMs()
        return when (val result = client.state(entityId)) {
            is HaCall.Ok -> {
                val entity = result.value
                val name = resolveName(entity, overrides)
                write(kind, glanceId) { it.putSnapshot(entity.toSnapshot(name, nowMs()), json) }
                entity.state
            }
            is HaCall.Failed -> {
                write(kind, glanceId) { prefs ->
                    prefs.putBlocker(result.blocker)
                    // Same rule as the app's `maskSecurityStates`: a lock we can't reach stops
                    // claiming to be locked. A light may keep its last reading — it shows its age.
                    if (kind != WidgetKind.LIGHT) prefs.maskState()
                }
                null
            }
        }
    }

    private suspend fun prefs(glanceId: GlanceId): Preferences =
        getAppWidgetState(context, definition, glanceId)

    private suspend fun write(
        kind: WidgetKind,
        glanceId: GlanceId,
        block: (MutablePreferences) -> Unit,
    ) {
        updateAppWidgetState(context, glanceId) { block(it) }
        glanceWidget(kind).update(context, glanceId)
    }

    private val definition: GlanceStateDefinition<Preferences> get() = PreferencesGlanceStateDefinition

    private companion object {
        const val LIGHT_CONFIRM_DELAY_MS = 700L

        /**
         * How soon after a fetch a render may trigger another. Long enough to swallow the redraws
         * a write causes, short enough that a widget coming back into view past the 60s security
         * freshness window always refetches.
         */
        const val MIN_RENDER_REFRESH_MS = 10_000L

        /** A little past the confirm window, so the lapse never fires before the UI stops offering it. */
        const val LAPSE_GRACE_MS = 250L
    }
}

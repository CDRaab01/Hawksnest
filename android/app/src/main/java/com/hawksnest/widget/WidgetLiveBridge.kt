package com.hawksnest.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.di.ApplicationScope
import com.hawksnest.widget.data.WidgetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app is running, its widgets are live for free.
 *
 * The socket is already streaming every entity change into `HaState`; a widget showing one of
 * those entities may as well be redrawn from the same stream instead of polling for something the
 * process already knows. This costs one collector and no extra network, and it is why opening the
 * app makes the home screen snap current.
 *
 * It is a bonus, not the mechanism — when the app is closed there is no socket, and widgets fall
 * back to reading on render and after each action. Nothing here is load-bearing for correctness.
 */
@Singleton
class WidgetLiveBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    private val repository: WidgetRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false
    private var targets: List<Target> = emptyList()
    private var targetsLoadedAt = 0L

    private data class Target(val kind: WidgetKind, val glanceId: GlanceId, val entityId: String)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // A StateFlow is already conflated, so the trailing delay is the whole throttle: a
            // firehose of entity deltas becomes one pass every few seconds over the newest map.
            connectionManager.state.entities.collect { entities ->
                sync(entities)
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private suspend fun sync(entities: Map<String, HassEntity>) {
        val now = System.currentTimeMillis()
        if (now - targetsLoadedAt > TARGET_TTL_MS) {
            targets = loadTargets()
            targetsLoadedAt = now
        }
        for (target in targets) {
            val entity = entities[target.entityId] ?: continue
            // Only write when the state actually moved; a redraw per delta would thrash the
            // launcher for entities whose attributes churn.
            if (repository.storedState(target.glanceId) == entity.state) continue
            repository.publish(target.kind, target.glanceId, entity)
        }
    }

    private suspend fun loadTargets(): List<Target> {
        val manager = GlanceAppWidgetManager(context)
        return WidgetKind.entries.flatMap { kind ->
            manager.getGlanceIds(glanceWidgetClass(kind)).mapNotNull { glanceId ->
                repository.storedEntityId(glanceId)?.let { Target(kind, glanceId, it) }
            }
        }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 3_000L

        /** How long the "which widgets exist" list is reused before being enumerated again. */
        const val TARGET_TTL_MS = 30_000L
    }
}

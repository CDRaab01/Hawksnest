package com.hawksnest.core.ha

import com.hawksnest.di.ApplicationScope
import com.hawksnest.util.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects and drives the active data [Source] — the Kotlin analogue of `src/store/connection.ts`.
 * Uses the live [HaSource] when a URL + token are saved, else the demo [FixtureSource]. Runs in an
 * app-scoped coroutine so the socket outlives any screen. Idempotent [start]; [reconnect] re-selects
 * after the credentials change (Settings save/disconnect).
 *
 * **The socket outlives any screen — it does not outlive the app being on screen.** It used to:
 * it opened in `Application.onCreate` and nothing ever closed it, which was harmless while an
 * Android process died soon after backgrounding. Then push arrived, and its foreground service
 * keeps this process alive indefinitely — so the phone held an unfiltered `subscribe_entities`
 * stream in a pocket, waking the radio and CPU for every state change in the house, all day, to
 * update a UI nobody was looking at. (It also meant a bare widget tap or a reboot, both of which
 * create the process with no activity, opened a full session.) [setForeground] is the gate: the
 * live source runs only while an activity is started, plus [BACKGROUND_GRACE_MS] so an app switch
 * or a rotation is not a reconnect. Nothing that works with the app closed rides this socket —
 * push has its own ntfy connection and widgets read over REST (`WidgetHaClient`).
 */
@Singleton
class ConnectionManager @Inject constructor(
    val state: HaState,
    private val fixtureSource: FixtureSource,
    private val credentialStore: CredentialStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val controlGate: ControlGate,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Entity ids with a user control in flight (see [ControlGate.pending]). */
    val pendingControls = controlGate.pending

    /** Human-readable control failures for the app-level snackbar (see [ControlGate.errors]). */
    val controlErrors = controlGate.errors

    @Volatile
    private var current: Source? = null
    private var started = false
    private val selectMutex = Mutex()

    // Main-thread only (written by [start] / [setForeground], both called from lifecycle callbacks).
    private var foreground = false
    private var backgroundJob: Job? = null

    // Guarded by [selectMutex].
    private var migrated = false
    private var suspendedForBackground = false

    /**
     * Arm the manager (called from the app onCreate). Connects only if the app is already on
     * screen; otherwise the first [setForeground] does — a process created for a widget tap, a
     * boot broadcast or the push service has no use for a session.
     */
    fun start() {
        if (started) return
        started = true
        if (foreground) scope.launch { select() }
    }

    /**
     * Tell the manager whether any activity is started. Main thread only.
     *
     * Going to the background stops the live source after [BACKGROUND_GRACE_MS]; coming back
     * cancels a pending stop, or reconnects if it already happened. The stop goes through
     * [HaState.setStatus] like any other drop, so lock/alarm state is masked the moment the
     * stream that kept it honest goes away, and the return shows the ordinary dimmed
     * "Reconnecting" second rather than an hour-old house presented as live.
     */
    fun setForeground(isForeground: Boolean) {
        if (foreground == isForeground) return
        foreground = isForeground
        backgroundJob?.cancel()
        backgroundJob = null
        if (!started) return
        if (isForeground) {
            scope.launch { resume() }
        } else {
            backgroundJob = scope.launch {
                delay(BACKGROUND_GRACE_MS)
                suspendForBackground()
            }
        }
    }

    private suspend fun resume() = selectMutex.withLock {
        if (current != null && !suspendedForBackground) return@withLock
        // The grace window counts from the drop, which for a background stop may be hours ago —
        // without this the return would open on the full Offline screen for the ~1s it takes to
        // reconnect. A genuinely unreachable HA still gets there, 120s from now.
        state.restartStaleClock()
        selectLocked()
    }

    private suspend fun suspendForBackground() = selectMutex.withLock {
        // Only the live source holds anything open; the demo source has nothing to stop.
        val live = current as? HaSource ?: return@withLock
        live.stop()
        suspendedForBackground = true
        state.setNextRetryAt(null)
        state.setStatus(ConnectionStatus.CONNECTING)
    }

    /** Re-select + restart the source after the saved credentials change. */
    fun reconnect() {
        scope.launch { select() }
    }

    /** Skip the remainder of the current reconnect backoff (the Offline screen's "Retry now"). */
    fun retryNow() {
        current?.retryNow()
    }

    private suspend fun select() = selectMutex.withLock { selectLocked() }

    private suspend fun selectLocked() {
        if (!migrated) {
            // Upgrade any pre-encryption install to a Keystore-wrapped token before first use.
            credentialStore.migrateLegacyToken()
            migrated = true
        }
        suspendedForBackground = false
        val url = credentialStore.haUrl.firstOrNull()
        val token = credentialStore.haToken.firstOrNull()
        current?.stop()
        current = if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
            HaSource(okHttpClient, json, state, scope, url, token)
        } else {
            fixtureSource
        }
        current?.start()
    }

    /** Perform a service call through the active source (non-optimistic — the echo reconciles). */
    suspend fun callService(domain: String, service: String, data: ServiceData) {
        // settled(): a launcher shortcut fires from onCreate — before the activity's onStart has
        // even asked for the socket back — and must wait for it rather than fail "Not connected".
        settled()?.callService(domain, service, data)
    }

    /**
     * The user-facing control path: [callService] wrapped in [ControlGate] — crash-safe (failures
     * land on [controlErrors], never as an uncaught coroutine exception) with [pendingControls]
     * tracking until HA echoes. Fire-and-forget in the app scope so an in-flight control outlives
     * the screen. All tap/slide/toggle handlers go through here; use raw [callService] only where
     * a screen handles its own errors (e.g. lock keypad codes).
     */
    fun control(
        entityId: String,
        service: String,
        label: String,
        extra: Map<String, Any?> = emptyMap(),
        awaitEcho: Boolean = true,
        domain: String = entityId.substringBefore('.'),
    ) {
        controlGate.launch(entityId, label, awaitEcho) {
            callService(domain, service, ServiceData(entityId = entityId, extra = extra))
        }
    }

    /** Read one automation's Config-API config (live HA REST; in-memory in demo). Null if absent. */
    suspend fun getAutomationConfig(id: String): JsonObject? = current?.getAutomationConfig(id)

    /** Create/update an automation via the Config API (live HA REST; in-memory in demo). */
    suspend fun saveAutomationConfig(config: JsonObject) {
        current?.saveAutomationConfig(config)
    }

    /** Delete an automation via the Config API (live HA REST; in-memory in demo). */
    suspend fun deleteAutomationConfig(id: String) {
        current?.deleteAutomationConfig(id)
    }

    /** Recent state history for one entity (live HA over WS; synthesized in demo). */
    suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> =
        current?.fetchHistory(entityId, hours) ?: emptyList()

    /** History of one entity attribute as (timeMs, value) pairs — real ring event times via eventId. */
    suspend fun fetchAttributeHistory(entityId: String, startMs: Long, endMs: Long, attr: String): List<Pair<Long, String>> =
        current?.fetchAttributeHistory(entityId, startMs, endMs, attr) ?: emptyList()

    /** The logbook over `[startMs, endMs]` (live HA over WS; synthesized in demo). */
    suspend fun fetchLogbook(startMs: Long, endMs: Long): List<com.hawksnest.core.logic.LogEvent> =
        current?.fetchLogbook(startMs, endMs) ?: emptyList()

    /**
     * The active source, after giving an in-progress (re)connect a moment to land.
     *
     * A camera player restarts the instant the activity does, which after a background stop is
     * ~1s BEFORE the socket is back — and "not connected" reads to the live ladder as "this tier
     * failed", stepping a healthy camera down to HLS or a snapshot. The same race existed on a cold
     * start from a doorbell notification. Bounded, so a genuinely offline HA still fails promptly.
     */
    private suspend fun settled(): Source? {
        if (state.status.value == ConnectionStatus.CONNECTING) {
            withTimeoutOrNull(CONNECT_WAIT_MS) {
                state.status.first { it != ConnectionStatus.CONNECTING }
            }
        }
        return current
    }

    /** On-demand live-stream URL for a camera (HLS from live HA; bundled demo clip in demo). */
    suspend fun streamUrl(entityId: String): String? = settled()?.streamUrl(entityId)

    /** Recorded camera events over `[startMs, endMs]` for the timeline scrubber. */
    suspend fun fetchCameraEvents(camera: String, startMs: Long, endMs: Long): List<com.hawksnest.core.logic.CameraEvent> =
        current?.fetchCameraEvents(camera, startMs, endMs) ?: emptyList()

    /** Continuous-recording spans for a Frigate camera — the timeline's footage lane ([] if unsupported). */
    suspend fun fetchCameraFootage(camera: String, startMs: Long, endMs: Long): List<com.hawksnest.core.logic.FootageSpan> =
        current?.fetchCameraFootage(camera, startMs, endMs) ?: emptyList()

    /** Recorded-footage URL for [camera] over `[startMs, endMs]` (null if unsupported). */
    fun recordingUrlAt(camera: String, startMs: Long, endMs: Long): String? =
        current?.recordingUrlAt(camera, startMs, endMs)

    /** As [recordingUrlAt], but signed where the backend requires it (see [Source.signedRecordingUrlAt]). */
    suspend fun signedRecordingUrlAt(camera: String, startMs: Long, endMs: Long): String? =
        settled()?.signedRecordingUrlAt(camera, startMs, endMs)

    /**
     * A signed, downloadable mp4 of an arbitrary range — clip export
     * (see [Source.signedClipExportUrl]). Null means "don't start a download".
     */
    suspend fun signedClipExportUrl(camera: String, startMs: Long, endMs: Long): String? =
        current?.signedClipExportUrl(camera, startMs, endMs)

    /** Days of continuous recording Frigate keeps for [camera] (see [Source.frigateRetentionDays]). */
    suspend fun frigateRetentionDays(camera: String): Double? =
        current?.frigateRetentionDays(camera)

    /**
     * The HA token, for media requests that must carry `Authorization` themselves.
     *
     * ExoPlayer fetches HLS manifests and segments outside the source layer, so it cannot borrow
     * the auth the rest of the app uses. Frigate VOD needs BOTH: `authSig` on segments (a Bearer
     * token does not satisfy that check) and a Bearer token on the *nested* `index-*.m3u8`
     * manifest, because HA's signed-path auth validates the EXACT path signed — a signature minted
     * for `master.m3u8` does not authorise its sibling. Null when running on the fixture source.
     */
    suspend fun haToken(): String? = credentialStore.haToken.firstOrNull()?.takeIf { it.isNotBlank() }

    /** True when the active source can negotiate WebRTC (live HA, not demo). */
    fun supportsWebRtc(): Boolean = current?.supportsWebRtc() ?: false

    /** Begin a WebRTC live session (see [Source.webrtcOffer]). Null when unsupported. */
    suspend fun webrtcOffer(
        entityId: String,
        offerSdp: String,
        onSignal: (WebRtcSignal) -> Unit,
    ): WebRtcHandle? = settled()?.webrtcOffer(entityId, offerSdp, onSignal)

    /** Push a local trickle ICE candidate for an in-flight WebRTC session. */
    suspend fun webrtcCandidate(
        entityId: String,
        sessionId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) {
        current?.webrtcCandidate(entityId, sessionId, candidate, sdpMid, sdpMLineIndex)
    }

    private companion object {
        /**
         * How long the socket survives the app leaving the screen. Long enough that switching to
         * another app and back, a rotation, or a permission dialog is not a reconnect — and that a
         * control tapped on the way out still gets its echo — short enough to be irrelevant to a
         * phone that spends the next eight hours in a pocket.
         */
        const val BACKGROUND_GRACE_MS = 30_000L

        /** Upper bound on [settled]'s wait for an in-progress connect. */
        const val CONNECT_WAIT_MS = 8_000L
    }
}

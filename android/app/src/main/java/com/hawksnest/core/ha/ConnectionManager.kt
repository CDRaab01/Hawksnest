package com.hawksnest.core.ha

import com.hawksnest.di.ApplicationScope
import com.hawksnest.util.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Start the active source once (called from the app onCreate). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Upgrade any pre-encryption install to a Keystore-wrapped token, then connect.
            credentialStore.migrateLegacyToken()
            select()
        }
    }

    /** Re-select + restart the source after the saved credentials change. */
    fun reconnect() {
        scope.launch { select() }
    }

    private suspend fun select() = selectMutex.withLock {
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
        current?.callService(domain, service, data)
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

    /** On-demand live-stream URL for a camera (HLS from live HA; bundled demo clip in demo). */
    suspend fun streamUrl(entityId: String): String? = current?.streamUrl(entityId)

    /** Recorded camera events over `[startMs, endMs]` for the timeline scrubber. */
    suspend fun fetchCameraEvents(camera: String, startMs: Long, endMs: Long): List<com.hawksnest.core.logic.CameraEvent> =
        current?.fetchCameraEvents(camera, startMs, endMs) ?: emptyList()

    /** Recorded-footage URL for [camera] over `[startMs, endMs]` (null if unsupported). */
    fun recordingUrlAt(camera: String, startMs: Long, endMs: Long): String? =
        current?.recordingUrlAt(camera, startMs, endMs)

    /** True when the active source can negotiate WebRTC (live HA, not demo). */
    fun supportsWebRtc(): Boolean = current?.supportsWebRtc() ?: false

    /** Begin a WebRTC live session (see [Source.webrtcOffer]). Null when unsupported. */
    suspend fun webrtcOffer(
        entityId: String,
        offerSdp: String,
        onSignal: (WebRtcSignal) -> Unit,
    ): WebRtcHandle? = current?.webrtcOffer(entityId, offerSdp, onSignal)

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
}

package com.hawksnest.core.ha

import com.hawksnest.di.ApplicationScope
import com.hawksnest.util.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var current: Source? = null
    private var started = false

    /** Start the active source once (called from the app onCreate). */
    fun start() {
        if (started) return
        started = true
        scope.launch { select() }
    }

    /** Re-select + restart the source after the saved credentials change. */
    fun reconnect() {
        scope.launch { select() }
    }

    private suspend fun select() {
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

    /** Recent state history for one entity (live HA over WS; synthesized in demo). */
    suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> =
        current?.fetchHistory(entityId, hours) ?: emptyList()

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
}

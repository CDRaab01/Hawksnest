package com.hawksnest.core.net

import com.hawksnest.core.logic.RingDevice
import com.hawksnest.core.logic.RingFootage
import com.hawksnest.core.logic.RingTimeline
import com.hawksnest.core.logic.parseRingDevices
import com.hawksnest.core.logic.parseRingFootage
import com.hawksnest.core.logic.parseRingTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the `ring-timeline` service (sibling repo `hawksnest-automation`) — Ring's own recorded
 * footage timeline, which Home Assistant cannot provide.
 *
 * Reached through the SAME origin the app already talks to (the Hawksnest nginx pod on Tailscale
 * Serve `:8443`, which proxies `/ring-timeline/` to the ClusterIP service), so this needs no new
 * host, no new credential, and no new external surface. No HA token is sent: the service doesn't
 * authenticate callers — reaching the tailnet-only proxy IS the authorization, same as go2rtc.
 *
 * Every call returns null on failure rather than throwing: the player falls back to the ring-mqtt
 * selector path, so a timeline service being down degrades recorded playback instead of breaking
 * the camera screen.
 */
@Singleton
class RingTimelineClient @Inject constructor(client: OkHttpClient) {

    /**
     * The injected client is the one shared with the HA WebSocket, which sets `readTimeout(0)` —
     * no timeout at all, correct for a connection meant to stay open for hours and wrong for a REST
     * call. "Returns null on failure" only degrades gracefully when the call actually *fails*: a
     * request that stalls instead of erroring never throws, so it never returns null, so the
     * selector fallback never runs and the timeline waits forever with no error on screen.
     *
     * Bounding it turns that silent hang into a fast fall-back. [TIMEOUT_SECONDS] is generous, not
     * tight: measured calls land in 0.2–0.7 s, but nginx allows the upstream 60 s because paging a
     * busy camera's day through Ring's own API genuinely takes seconds, and timing out a slow-but-
     * working timeline would be a regression. 20 s matches the bound the selector path already
     * uses, so the worst case is symmetric either way.
     *
     * `newBuilder()` (as in [Go2rtcStreams.httpFetcher]) shares the connection pool and dispatcher
     * with the parent rather than standing up a second set of threads.
     */
    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun base(baseUrl: String): String = baseUrl.trimEnd('/') + PATH

    /** The service's camera list, or null when it isn't reachable. */
    suspend fun devices(baseUrl: String): List<RingDevice>? {
        if (baseUrl.isBlank()) return null
        val body = get("${base(baseUrl)}/cameras") ?: return null
        return try {
            parseRingDevices(json.parseToJsonElement(body) as? JsonArray ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    /** One camera's recordings over `[fromMs, toMs]` with their playable URLs, or null on failure. */
    suspend fun timeline(
        baseUrl: String,
        deviceId: Long,
        cameraName: String,
        fromMs: Long,
        toMs: Long,
    ): RingTimeline? {
        if (baseUrl.isBlank()) return null
        val url = "${base(baseUrl)}/timeline?device_id=$deviceId&from=$fromMs&to=$toMs"
        val body = get(url) ?: return null
        return try {
            parseRingTimeline(json.parseToJsonElement(body) as? JsonObject ?: return null, cameraName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One camera's **24/7 continuous track** over `[fromMs, toMs]` — the footage that exists
     * between the events — or null when the service isn't reachable.
     *
     * A camera without 24/7 recording answers 200 with an empty list, which parses to
     * [RingFootage.EMPTY]: a real "no continuous track", distinct from the null that means the
     * service is down. The player draws no lane for either, but only null falls back.
     */
    suspend fun footage(baseUrl: String, deviceId: Long, fromMs: Long, toMs: Long): RingFootage? {
        if (baseUrl.isBlank()) return null
        val url = "${base(baseUrl)}/footage?device_id=$deviceId&from=$fromMs&to=$toMs"
        val body = get(url) ?: return null
        return try {
            parseRingFootage(json.parseToJsonElement(body) as? JsonObject ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /** Deliberately not under `/api/` — that path is proxied to Home Assistant. */
        const val PATH = "/ring-timeline"

        /** Ceiling for one timeline call; see the note on [client]. */
        const val TIMEOUT_SECONDS = 20L
    }
}

package com.hawksnest.core.net

import com.hawksnest.core.logic.RingDevice
import com.hawksnest.core.logic.RingTimeline
import com.hawksnest.core.logic.parseRingDevices
import com.hawksnest.core.logic.parseRingTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
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
class RingTimelineClient @Inject constructor(private val client: OkHttpClient) {

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

    private companion object {
        /** Deliberately not under `/api/` — that path is proxied to Home Assistant. */
        const val PATH = "/ring-timeline"
    }
}

package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Builds the direct-to-camera RTSP URL for a Reolink camera, and (de)serializes the camera→IP map
 * the user configures in Settings.
 *
 * **Android-only — deliberately has no web twin.** Browsers cannot play RTSP at any level, so the
 * web client's best live transport is and remains WebRTC via go2rtc. This is the one place the two
 * platforms' live ladders legitimately differ, rather than one lagging the other.
 *
 * ## Why direct, when go2rtc already carries these streams
 *
 * go2rtc re-packages RTSP into WebRTC, which is continuous but adds a relay hop and (here) rides
 * TCP. Playing the camera's own RTSP is what the vendor app does and is the shortest path there is.
 * It is the TOP tier, not a replacement: it needs credentials, a reachable camera IP, and one of
 * the camera's few RTSP sessions, so anything that can't satisfy all three steps down to go2rtc.
 */

/**
 * Reolink's fixed path for the high-quality stream. The `h264` in it is a **label, not a promise** —
 * the camera serves whatever codec its encoder is set to on this path, and Reolink's `SetEnc` will
 * silently keep H.265 for combinations it doesn't support. ExoPlayer decodes both, so this is
 * mostly harmless here; it is go2rtc's WebRTC tier that breaks on HEVC. Verify with ffprobe after
 * any encoder change rather than trusting the path name.
 */
const val REOLINK_MAIN_STREAM_PATH = "h264Preview_01_main"

/** Reolink's RTSP port. Fixed across the fleet; not configurable in the app on purpose. */
private const val RTSP_PORT = 554

/**
 * `rtsp://user:pass@ip:554/<path>` with percent-encoded userinfo.
 *
 * media3's RTSP client reads credentials from the URI's userinfo and answers the camera's
 * basic/digest challenge with them, so this is how auth is delivered — there is no header seam.
 * Encoding matters: the shared camera password is generated and can contain characters (`@`, `/`,
 * `:`) that would otherwise terminate the userinfo early and produce a wrong host.
 */
fun reolinkRtspUrl(
    ip: String,
    user: String,
    pass: String,
    path: String = REOLINK_MAIN_STREAM_PATH,
): String = "rtsp://${enc(user)}:${enc(pass)}@$ip:$RTSP_PORT/$path"

/** URL-encode for userinfo. `URLEncoder` is form-encoding, so `+` must become `%20`. */
private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/**
 * Strips credentials from an RTSP URL so it can be logged or shown.
 *
 * Every diagnostic path must go through this: an RTSP URL embeds the camera password in plain
 * text, and a crash reporter or a debug toast is enough to leak it.
 */
fun redactRtspUrl(url: String): String = Regex("://[^@/]*@").replace(url, "://<redacted>@")

/**
 * Whether [ip] is a plausible dotted-quad, for Settings validation.
 *
 * Deliberately syntax-only: whether the address is *reachable* is a question for the player's
 * fail-fast, not a text field. Hostnames are rejected because the map is populated from DHCP
 * reservations, and a typo'd hostname would silently resolve to something else on the LAN.
 */
fun isPlausibleIpv4(ip: String): Boolean {
    val parts = ip.trim().split(".")
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && (p.toIntOrNull() ?: -1) in 0..255
    }
}

/**
 * Serialize the camera→IP map for storage. Sorted so the stored string is stable across saves
 * (an unordered map would rewrite DataStore on every edit and churn its history).
 */
fun encodeCameraIps(ips: Map<String, String>): String = JsonObject(
    ips.filterValues { it.isNotBlank() }
        .toSortedMap()
        .mapValues { (_, v) -> JsonPrimitive(v.trim()) },
).toString()

/**
 * Parse the stored camera→IP map. Returns empty on anything malformed rather than throwing: a
 * corrupt entry must disable the RTSP tier (and fall back to go2rtc), never break the camera screen.
 */
fun decodeCameraIps(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (k, v) ->
            val ip = v.jsonPrimitive.contentOrNull?.trim()
            if (!ip.isNullOrBlank() && isPlausibleIpv4(ip)) k to ip else null
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

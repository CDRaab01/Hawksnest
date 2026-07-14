package com.hawksnest.ui.cameras

import java.net.URLEncoder

/**
 * go2rtc WebSocket signaling URL from the connected HA origin (http→ws, https→wss) plus the
 * nginx-proxied `/go2rtc/` path. `src` is the go2rtc stream name (= the HA camera base). Shared
 * by the Talk button (sendonly audio) and the direct-live player (recvonly video+audio).
 */
fun go2rtcWsUrl(baseUrl: String, src: String): String {
    val origin = baseUrl.trimEnd('/')
    val wsOrigin = when {
        origin.startsWith("https://") -> "wss://" + origin.removePrefix("https://")
        origin.startsWith("http://") -> "ws://" + origin.removePrefix("http://")
        else -> origin
    }
    return "$wsOrigin/go2rtc/api/ws?src=${URLEncoder.encode(src, "UTF-8")}"
}

/**
 * Session circuit-breaker for the go2rtc direct-live **media** path. Signaling (WS via nginx) can
 * succeed while media (WebRTC to `GO2RTC_HOST_IP:8555`) can't be reached — before the §7c host
 * forwarder is up, or off the tailnet. The first camera whose media fails flips this to false and
 * every camera after skips the go2rtc tier for the rest of the process (no repeated multi-second
 * stalls); it drops straight to the HA WebRTC path instead. A success flips it true.
 */
object Go2rtcHealth {
    @Volatile private var mediaHealthy: Boolean? = null

    fun report(ok: Boolean) {
        mediaHealthy = ok
    }

    /** Best-guess for whether the direct-go2rtc tier is worth attempting (media not known-broken). */
    fun maybeAvailable(): Boolean = mediaHealthy != false
}

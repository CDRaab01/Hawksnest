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

// `Go2rtcHealth` moved to `core/net/Go2rtcStreams.kt`, alongside the stream-list cache that also
// consults it — the same pairing the web `lib/go2rtc.ts` uses, and `core` cannot depend on `ui`.

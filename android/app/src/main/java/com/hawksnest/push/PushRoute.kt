package com.hawksnest.push

import java.net.URLDecoder

/**
 * Classifies an ntfy message into a Hawksnest push kind and works out what a tap
 * should open. Pure (no Android deps) so the routing is unit-tested. Keys off the
 * tags the HA automations set (`bell`, `shield`, `rotating_light`) with a title
 * fallback, so it degrades gracefully if a message arrives without tags.
 */
enum class PushKind {
    Doorbell,
    Alarm,
    Generic,
}

object PushRoute {
    /** Where a tapped notification lands: Home is the only real nav route; a doorbell
     *  additionally opens its camera in the lightbox overlay (see [cameraOf]). */
    const val ROUTE_HOME = "home"

    fun kindOf(msg: NtfyMessage): PushKind {
        val tags = msg.tags.map { it.lowercase() }
        val title = msg.title.lowercase()
        return when {
            "bell" in tags || "doorbell" in title -> PushKind.Doorbell
            "rotating_light" in tags || "shield" in tags || title.startsWith("alarm") ->
                PushKind.Alarm
            else -> PushKind.Generic
        }
    }

    /**
     * The logical camera id a doorbell notification should open, or null. The HA
     * doorbell automation puts it in the message's `click` URL as `?camera=<id>`
     * (the id is `camera.<base>`, matching `CameraUi.id`); a tap deep-links straight
     * to that camera's live view. Absent/unparseable → null (fall back to Home).
     */
    fun cameraOf(msg: NtfyMessage): String? {
        val click = msg.click ?: return null
        // Grab the `camera` query value without needing android.net.Uri (keeps this
        // pure/JVM-testable). Matches `?camera=x` or `&camera=x`, stops at the next `&`.
        val raw = Regex("[?&]camera=([^&\\s]+)").find(click)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%20"), "UTF-8") }
            .getOrDefault(raw)
        return decoded.takeIf { it.isNotBlank() }
    }
}

package com.hawksnest.push

/**
 * Classifies an ntfy message into a Hawksnest push kind and the in-app
 * destination its notification should open. Pure (no Android deps) so the
 * doorbell/alarm routing is unit-tested. Keys off the tags the HA automations
 * set (`bell`, `shield`, `rotating_light`) with a title fallback, so it degrades
 * gracefully if a message arrives without tags.
 */
enum class PushKind {
    Doorbell,
    Alarm,
    Generic,
}

object PushRoute {
    /** Navigation route opened when the notification is tapped. */
    const val ROUTE_CAMERAS = "cameras"
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

    /** Deep-link target: a doorbell opens the camera wall; everything else, Home. */
    fun routeFor(kind: PushKind): String =
        if (kind == PushKind.Doorbell) ROUTE_CAMERAS else ROUTE_HOME
}

package com.hawksnest.push

import com.hawksnest.core.logic.CameraStart
import java.net.URLDecoder

/**
 * Classifies an ntfy message into a Hawksnest push kind and works out what a tap
 * should open. Pure (no Android deps) so the routing is unit-tested. Keys off the
 * tags the HA automations set (`bell`, `shield`, `rotating_light`, `walking`/`dog`/
 * `cat`) with a title fallback, so it degrades gracefully if a message arrives
 * without tags.
 */
enum class PushKind {
    Doorbell,
    Alarm,

    /** Frigate saw a person on a camera. Only ever sent while the alarm is armed
     *  (the HA automation gates on it), so this really is a security event. */
    Person,

    /** Frigate saw a dog or cat — the same pipeline, but noise rather than alarm,
     *  so it gets its own channel the user can mute independently. */
    Pet,
    Generic,
}

/**
 * Where a notification tap, or one of its buttons, lands. Every destination is a thing in the
 * house: a camera, a device's screen, or (for an alarm that went off) Home with the alert pinned
 * on top, because Home's alarm control is where you act on it.
 */
sealed interface PushTarget {
    data class Camera(
        val cameraId: String,
        val eventId: String? = null,
        val start: CameraStart = CameraStart.LIVE,
    ) : PushTarget

    data class Entity(val entityId: String) : PushTarget

    /** Home, optionally with the notification's title and text shown as a banner. */
    data class Home(val banner: Boolean = false) : PushTarget
}

/** One button on a notification. At most three, which is all Android shows. */
sealed interface PushAction {
    val label: String

    /** Opens the app on [target]. */
    data class Open(override val label: String, val target: PushTarget) : PushAction

    /** Re-posts the doorbell notification with the three quick replies as its buttons. */
    data class ReplyMenu(val cameraId: String) : PushAction {
        override val label: String get() = "Reply"
    }

    /**
     * Arms the panel away from the notification itself. The only security action a notification
     * carries, and deliberately one that makes the house safer: nothing on a notification can
     * disarm or unlock (the owner's call, 2026-09-28; the same rule the launcher shortcuts follow).
     */
    data class ArmAway(val entityId: String) : PushAction {
        override val label: String get() = "Arm away"
    }
}

object PushRoute {
    /** Where a tapped notification lands: Home is the only real nav route; a doorbell
     *  additionally opens its camera in the lightbox overlay (see [cameraOf]). */
    const val ROUTE_HOME = "home"

    /**
     * The device a tap should open, from a `click` URL of the form `…/entity/<entity_id>`, the
     * same path the web app serves, so the plain ntfy app and a browser land on the same screen.
     * Null when the URL names no entity or names something that is not an entity id.
     */
    fun entityOf(msg: NtfyMessage): String? {
        val click = msg.click ?: return null
        val raw = Regex("/entity/([^/?#\\s]+)").find(click)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.takeIf { ENTITY_ID.matches(it) }
    }

    private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    /** An alarm that went off: the one alert that lands on Home with its text pinned on top. */
    fun isAlarmTriggered(msg: NtfyMessage): Boolean =
        kindOf(msg) == PushKind.Alarm && mentions(msg, "triggered")

    private fun isAlarmDisarmed(msg: NtfyMessage): Boolean =
        kindOf(msg) == PushKind.Alarm && mentions(msg, "disarmed")

    private fun mentions(msg: NtfyMessage, word: String): Boolean =
        word in msg.title.lowercase() || word in msg.body.lowercase()

    /**
     * Where tapping the notification itself goes. First match wins:
     *  1. an alarm that went off → Home, with the alert as a banner and Off one tap away;
     *  2. a device named in the link → that device's screen (the garage door, the alarm panel,
     *     a device with a flat battery);
     *  3. a camera named in the link → that camera, at the alert's moment when there is one;
     *  4. otherwise Home.
     */
    fun tapTarget(msg: NtfyMessage): PushTarget {
        if (isAlarmTriggered(msg)) return PushTarget.Home(banner = true)
        entityOf(msg)?.let { return PushTarget.Entity(it) }
        cameraOf(msg)?.let { return PushTarget.Camera(it, eventOf(msg)) }
        return PushTarget.Home()
    }

    /**
     * The buttons a notification carries, most useful first, never more than three.
     *
     * Security policy: the only button that changes the house is [PushAction.ArmAway], offered on
     * a "disarmed" alert. Nothing here disarms or unlocks.
     */
    fun actionsFor(msg: NtfyMessage): List<PushAction> {
        val kind = kindOf(msg)
        val camera = cameraOf(msg)
        val event = eventOf(msg)
        val entity = entityOf(msg)
        val actions = buildList {
            when {
                kind == PushKind.Doorbell && camera != null -> {
                    add(PushAction.Open("Watch", PushTarget.Camera(camera)))
                    add(PushAction.Open("Talk", PushTarget.Camera(camera, start = CameraStart.TALK)))
                    add(PushAction.ReplyMenu(camera))
                }
                (kind == PushKind.Person || kind == PushKind.Pet) && camera != null -> {
                    if (event != null) add(PushAction.Open("View clip", PushTarget.Camera(camera, event)))
                    if (kind == PushKind.Person || event == null) {
                        add(PushAction.Open("Live", PushTarget.Camera(camera)))
                    }
                }
                isAlarmTriggered(msg) -> {
                    add(PushAction.Open("Cameras", PushTarget.Home(banner = true)))
                    entity?.let { add(PushAction.Open("View alarm", PushTarget.Entity(it))) }
                }
                else -> {
                    entity?.let { add(PushAction.Open(entityLabel(it), PushTarget.Entity(it))) }
                    if (entity != null && isAlarmDisarmed(msg) && entity.startsWith("alarm_control_panel.")) {
                        add(PushAction.ArmAway(entity))
                    }
                    camera?.let {
                        val label = if (entity != null) "${cameraLabel(it)} camera" else "View camera"
                        add(PushAction.Open(label, PushTarget.Camera(it, event)))
                    }
                }
            }
        }
        return actions.take(3)
    }

    /** "View alarm", "View garage", or plain "View device": what the button opens, in two words. */
    private fun entityLabel(entityId: String): String = when {
        entityId.startsWith("alarm_control_panel.") -> "View alarm"
        "garage" in entityId -> "View garage"
        else -> "View device"
    }

    /** `camera.front_door_reolink` → "Front door reolink". */
    private fun cameraLabel(cameraId: String): String =
        cameraId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    fun kindOf(msg: NtfyMessage): PushKind {
        val tags = msg.tags.map { it.lowercase() }
        val title = msg.title.lowercase()
        return when {
            "bell" in tags || "doorbell" in title -> PushKind.Doorbell
            "rotating_light" in tags || "shield" in tags || title.startsWith("alarm") ->
                PushKind.Alarm
            // `walking`/`dog`/`cat` are what `hawksnest_push_camera_object` sets —
            // valid ntfy emoji shortcodes, so the same tags also render sensibly in
            // the plain ntfy app. Ordered after Alarm so a hypothetical message
            // carrying both keeps its alarm classification.
            "walking" in tags -> PushKind.Person
            "dog" in tags || "cat" in tags -> PushKind.Pet
            else -> PushKind.Generic
        }
    }

    /**
     * The logical camera id a doorbell notification should open, or null. The HA
     * doorbell automation puts it in the message's `click` URL as `?camera=<id>`
     * (the id is `camera.<base>`, matching `CameraUi.id`); a tap deep-links straight
     * to that camera's live view. Absent/unparseable → null (fall back to Home).
     */
    fun cameraOf(msg: NtfyMessage): String? = queryParam(msg, "camera")

    /**
     * The Frigate event id a tap should land on, or null.
     *
     * The camera-object automation appends `&event=<id>` so a tap opens the moment
     * that actually triggered the alert rather than the live view — by the time you
     * look at your phone, whatever it saw has usually moved on. Doorbell and alarm
     * notifications carry no event, so this is null for them and the tap still just
     * opens the camera.
     */
    fun eventOf(msg: NtfyMessage): String? = queryParam(msg, "event")

    /**
     * Whether an "Arm away" read-back means it took. Arming counts: the exit delay runs for a while
     * and the notification should not claim failure while the panel is doing exactly what it was
     * asked to.
     */
    fun armAwayTook(state: String?): Boolean = state == "armed_away" || state == "arming"

    /** What the notification says after "Arm away", from the panel state HA read back (null: no read). */
    fun armAwayOutcome(state: String?): String = when (state) {
        "armed_away" -> "Armed away."
        "arming" -> "Arming away. The exit delay is running."
        null -> "Sent, but Home Assistant didn't confirm it. Open Hawksnest to check."
        else -> "Not armed. Home Assistant still says ${state.replace('_', ' ')}."
    }

    /** What the doorbell notification says after a quick reply. "Sent", as the in-app sheet says:
     *  go2rtc took the message, and nothing reports whether the speaker played it. */
    fun replyOutcome(label: String, sent: Boolean): String =
        if (sent) "Sent “$label”" else "Couldn't play that. Open Hawksnest to try Talk."

    /** Read a query value out of the `click` URL without android.net.Uri, so this
     *  stays pure and JVM-testable. Stops at the next `&`. */
    private fun queryParam(msg: NtfyMessage, name: String): String? {
        val click = msg.click ?: return null
        val raw = Regex("[?&]$name=([^&\\s]+)").find(click)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%20"), "UTF-8") }
            .getOrDefault(raw)
        return decoded.takeIf { it.isNotBlank() }
    }
}

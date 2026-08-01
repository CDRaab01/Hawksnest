package com.hawksnest.core.logic

/*
 * The scene-pad widget's rules — a home-screen copy of a Zooz ZEN32 wall controller.
 *
 * Kept out of WidgetModel.kt because this is the one widget whose model is about a *device's
 * faceplate* rather than an entity's state, and it carries enough of its own vocabulary (keys,
 * LED colours, the preset each key fires) to be worth reading on its own. Everything here is
 * Compose-free and unit-tested, for the same reason the rest of the widget layer is: a widget is
 * drawn by the launcher in a process that may have just been created, which is the hardest surface
 * in the app to test any other way.
 */

/**
 * The five buttons on the plate, in the order they physically appear.
 *
 * Verified against the hardware (2026-08-01) rather than assumed: the **relay is the large button
 * at the top**, and keys one to four are a 2×2 grid beneath it, reading left to right then down.
 * The planning note for this widget had the relay at the bottom, which would have put every layout
 * constant upside down.
 *
 * The numbering matches Zooz's own: HA's `event.…_scene_00N` entities are keys 1-4 and
 * `scene_005` is the relay, and the LED configuration parameters run "Relay, Button 1…4".
 */
enum class ScenePadKey { RELAY, ONE, TWO, THREE, FOUR }

/** The four small keys, in faceplate order. */
val SCENE_PAD_KEYS: List<ScenePadKey> =
    listOf(ScenePadKey.ONE, ScenePadKey.TWO, ScenePadKey.THREE, ScenePadKey.FOUR)

/**
 * The colours a ZEN32 LED can actually be, named rather than valued.
 *
 * Names, not `Color`s, because this is `core/logic` — it must stay Compose-free and testable, and
 * a widget is drawn in both light and dark theme without being redrawn, so the *value* has to be a
 * day/night pair resolved at draw time (`widget/ui/ScenePad.kt`). Same split as [Channel].
 *
 * The seven entries and their order are the device's own, read off the switch's configuration
 * parameter 6 metadata rather than transcribed from a manual.
 */
enum class LedColor { WHITE, BLUE, GREEN, RED, MAGENTA, YELLOW, CYAN }

/**
 * What the LEDs are set to on the controller in Cooper's bedroom, measured from Z-Wave
 * configuration parameters 6-10 on 2026-08-01.
 *
 * A default, not a fact about the widget: every instance stores its own five colours and the
 * configuration screen lets the owner change them. Defaulting to a real plate rather than to five
 * identical whites means the common case — the household that has one of these — matches the wall
 * on the first placement, which is the entire point of the widget.
 */
val ZEN32_DEFAULT_LEDS: Map<ScenePadKey, LedColor> = mapOf(
    ScenePadKey.RELAY to LedColor.GREEN,
    ScenePadKey.ONE to LedColor.YELLOW,
    ScenePadKey.TWO to LedColor.RED,
    ScenePadKey.THREE to LedColor.BLUE,
    ScenePadKey.FOUR to LedColor.MAGENTA,
)

/**
 * Below this the pad drops its header and is nothing but the plate.
 *
 * It needs its own threshold rather than [sizeTier]'s, and the number is higher: every usable
 * placement of a scene pad clears 120dp, so the shared COMPACT/FULL line would classify all of
 * them as FULL and never fire. The plate alone wants about 150dp — a relay row and two key rows
 * with targets worth aiming at — and the header costs another two lines plus a spacer on top.
 */
const val SCENE_PAD_HEADER_MIN_DP = 190

/** Whether a pad this tall can afford its header. */
fun scenePadShowsHeader(heightDp: Int): Boolean = heightDp >= SCENE_PAD_HEADER_MIN_DP

/**
 * A scene pad's title. The room if HA knows one, else the entity's own name.
 *
 * Same rule and the same reason as [temperatureTitle]: the widget is named for the plate's
 * location, and the entity behind it is a WLED preset selector whose name ("Cooper's Bedroom
 * Preset") describes the plumbing rather than the place.
 */
fun scenePadTitle(room: String?, entityName: String?): String =
    room?.trim()?.takeIf { it.isNotEmpty() }
        ?: entityName?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Scene pad"

/** One key as the widget should draw it. */
data class ScenePadKeyView(
    val key: ScenePadKey,
    /** The preset this key selects, or null when the owner left the slot empty. */
    val preset: String?,
    val led: LedColor,
    /** This key's preset is the one currently selected — its LED burns at full strength. */
    val active: Boolean,
    /** False when there is nothing to send: no preset assigned, or no reading to compare against. */
    val enabled: Boolean,
)

/** Everything the scene pad draws, decided here so the Glance layer stays dumb. */
data class ScenePadView(
    val name: String,
    /** The live preset, or "Checking…" — the header line, since the keys carry no labels. */
    val presetLabel: String,
    /** The four small keys, in faceplate order. */
    val keys: List<ScenePadKeyView>,
    val relayLed: LedColor,
    /** False when no relay entity was configured; the big key then draws dead rather than lying. */
    val relayEnabled: Boolean,
    val pending: Boolean,
    val staleness: String?,
)

/**
 * Build the pad from the stored reading and the owner's configuration.
 *
 * ## Why the presets are configuration and not read from HA
 *
 * The obvious design is to take the four presets from the select's own `options`. That does not
 * work: the entity behind this widget is a WLED preset selector with **twelve** options, and which
 * four the physical keys fire is decided by a Home Assistant automation the widget cannot read
 * (the widget process speaks REST, and an automation's `variables` block is not state). So the
 * four presets are per-instance configuration, picked from the live `options` list on the
 * configuration screen — which does have the socket.
 *
 * ## What "active" means
 *
 * The select's state *is* the live preset name, so a key is active when its preset equals that
 * state. Nothing is active when the reading is missing or has not arrived yet: five dark keys is
 * the honest answer, and lighting one on a guess would be worse than lighting none.
 *
 * [relayEntityId] is write-only — the pad never reads it, so the big key shows no state. That is
 * the known gap; giving it live state means storing a second reading per widget, which is a change
 * to the persisted format and belongs in its own change.
 */
fun scenePadView(
    snapshot: WidgetSnapshot?,
    presets: Map<ScenePadKey, String>,
    leds: Map<ScenePadKey, LedColor>,
    relayEntityId: String?,
    room: String?,
    nowMs: Long,
    pendingSinceMs: Long? = null,
): ScenePadView {
    val pending = widgetPending(pendingSinceMs, nowMs)
    val live = snapshot?.state?.takeIf { it != "unknown" && it != "unavailable" }
    val led = { key: ScenePadKey -> leds[key] ?: ZEN32_DEFAULT_LEDS.getValue(key) }

    return ScenePadView(
        name = scenePadTitle(room, snapshot?.name),
        presetLabel = when {
            live != null -> live
            snapshot != null -> snapshot.state.replaceFirstChar { it.uppercaseChar() }
            else -> "Checking…"
        },
        keys = SCENE_PAD_KEYS.map { key ->
            val preset = presets[key]?.takeIf { it.isNotBlank() }
            ScenePadKeyView(
                key = key,
                preset = preset,
                led = led(key),
                // Never active on a missing reading — see the KDoc. A key whose preset is the live
                // one still stays enabled: pressing it re-selects, which is what the wall key does.
                active = preset != null && preset == live,
                enabled = preset != null && snapshot != null,
            )
        },
        relayLed = led(ScenePadKey.RELAY),
        relayEnabled = !relayEntityId.isNullOrBlank(),
        pending = pending,
        staleness = snapshot?.let { stalenessLabel(it.fetchedAtMs, nowMs) },
    )
}

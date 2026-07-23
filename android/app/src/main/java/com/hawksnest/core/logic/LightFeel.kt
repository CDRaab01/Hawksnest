package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** HA `brightness` attribute (0–255) as a whole percent; 0 when absent (HA drops it while off). */
fun brightnessPct(attributes: JsonObject): Int =
    attributes.num("brightness")?.let { (it / 2.55).roundToInt() } ?: 0

/**
 * Map a vertical drag on the light pillar to a brightness percent. Dragging up brightens, so a
 * negative Compose delta (y grows downward) raises the value. The full track height spans the
 * full 0–100 range; a zero-height track (not yet measured) leaves the value unchanged.
 */
fun dragToPct(startPct: Int, dragDeltaPx: Float, trackHeightPx: Float): Int {
    if (trackHeightPx <= 0f) return startPct
    return (startPct - dragDeltaPx / trackHeightPx * 100f).roundToInt().coerceIn(0, 100)
}

/** Brightness levels that tick a haptic as the dim gesture crosses them. */
val LIGHT_TICKS = listOf(0, 25, 50, 75, 100)

/**
 * The tick crossed between two brightness values, or null when none was. A fast flick can skip
 * several ticks in one frame — report the one nearest the new value (highest going up, lowest
 * going down) so a single buzz marks the landing zone rather than machine-gunning.
 */
fun tickCrossed(prevPct: Int, nextPct: Int, ticks: List<Int> = LIGHT_TICKS): Int? = when {
    nextPct > prevPct -> ticks.filter { it in (prevPct + 1)..nextPct }.maxOrNull()
    nextPct < prevPct -> ticks.filter { it in nextPct until prevPct }.minOrNull()
    else -> null
}

private const val WARMTH_MIN_K = 2000.0
private const val WARMTH_MAX_K = 6500.0

/**
 * How warm the light's current color is, 0f (coolest) to 1f (warmest), or null when the light
 * reports no color information at all. Prefers `color_temp_kelvin` normalized against the
 * light's own `min/max_color_temp_kelvin` range (defaulting to 2000–6500K); falls back to the
 * red-vs-blue balance of `rgb_color`. Deliberately a scalar, not a color — the UI lerps between
 * theme channels so no raw color ever originates here.
 */
fun lightWarmth(attributes: JsonObject): Float? {
    val kelvin = attributes.num("color_temp_kelvin")
    if (kelvin != null) {
        val min = attributes.num("min_color_temp_kelvin") ?: WARMTH_MIN_K
        val max = attributes.num("max_color_temp_kelvin") ?: WARMTH_MAX_K
        if (max <= min) return null
        return (1.0 - (kelvin - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    }
    val rgb = (attributes["rgb_color"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        ?.takeIf { it.size >= 3 }
        ?: return null
    // Red-heavy reads warm, blue-heavy reads cool; a neutral white sits in the middle.
    return ((rgb[0] - rgb[2]) / 510.0 + 0.5).toFloat().coerceIn(0f, 1f)
}

private const val WASH_MIN = 0.05f
private const val WASH_MAX = 0.16f
private const val WASH_NON_DIMMABLE = 0.09f

/**
 * Alpha for the pillar's glow wash at a given level — brighter light, stronger wash. Constants
 * mirror the web LightCard's warmth wash exactly so both clients glow alike.
 */
fun washAlpha(on: Boolean, dimmable: Boolean, pct: Int): Float = when {
    !on -> 0f
    !dimmable -> WASH_NON_DIMMABLE
    else -> WASH_MIN + pct.coerceIn(0, 100) / 100f * (WASH_MAX - WASH_MIN)
}

/**
 * The single service call for a released dim gesture. Dragging to the floor means "off" —
 * HA treats `brightness_pct: 0` inconsistently across integrations, so send a real `turn_off`.
 */
fun dimCommit(pct: Int): Pair<String, Map<String, Any?>> =
    if (pct <= 0) "turn_off" to emptyMap()
    else "turn_on" to mapOf("brightness_pct" to pct.coerceIn(1, 100))

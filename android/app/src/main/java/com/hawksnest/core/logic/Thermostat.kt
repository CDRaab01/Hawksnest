package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.atan2
import kotlin.math.roundToInt

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Dial geometry: a 270° arc opening downward (gap at the bottom, like a physical thermostat).
 * Angles use the Canvas convention — 0° at 3 o'clock, growing clockwise — so the arc runs
 * bottom-left (135°) up over the top and down to bottom-right (45°).
 */
const val DIAL_START_DEG = 135f
const val DIAL_SWEEP_DEG = 270f

/** Fallback span (± around the target) when HA doesn't report `min_temp`/`max_temp`. */
private const val FALLBACK_SPAN = 10.0

/**
 * Pure view-model for the thermostat dial. [channel] tints the arc by what the HVAC is
 * actually doing right now (heating = warm streak, cooling = cool effort, idle = neutral);
 * [adjustable] is false when there is no setpoint to move (read-only dial).
 */
data class ThermostatView(
    val min: Double,
    val max: Double,
    val step: Double,
    val target: Double?,
    val current: Double?,
    val unit: String,
    val channel: Channel?,
    val adjustable: Boolean,
)

fun thermostatView(attributes: JsonObject, rawState: String): ThermostatView {
    val target = attributes.num("temperature")
    val step = attributes.num("target_temp_step")?.takeIf { it > 0 } ?: 0.5
    val min = attributes.num("min_temp") ?: target?.minus(FALLBACK_SPAN) ?: 0.0
    val max = attributes.num("max_temp") ?: target?.plus(FALLBACK_SPAN) ?: 0.0
    val channel = when (attributes.str("hvac_action")) {
        "heating" -> Channel.STREAK
        "cooling" -> Channel.EFFORT
        else -> null
    }
    return ThermostatView(
        min = min,
        max = max,
        step = step,
        target = target,
        current = attributes.num("current_temperature"),
        unit = attributes.str("unit_of_measurement") ?: "°",
        channel = channel,
        adjustable = target != null && max > min && rawState != "unavailable" && rawState != "off",
    )
}

/** Position of a temperature along the arc, 0f at [min] to 1f at [max]. */
fun tempToFraction(t: Double, min: Double, max: Double): Float =
    if (max <= min) 0f else ((t - min) / (max - min)).toFloat().coerceIn(0f, 1f)

/** Temperature at an arc position, snapped to the device's [step]. */
fun fractionToTemp(f: Float, min: Double, max: Double, step: Double): Double {
    val raw = min + (max - min) * f.coerceIn(0f, 1f)
    val snapped = (raw / step).roundToInt() * step
    return snapped.coerceIn(min, max)
}

/**
 * Where a touch lands along the arc, 0f..1f — or null in the bottom dead gap, so a stray
 * thumb brushing past the opening can't slam the setpoint to an extreme.
 */
fun touchToFraction(x: Float, y: Float, cx: Float, cy: Float): Float? {
    val deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    val rel = (deg - DIAL_START_DEG + 360f) % 360f
    return if (rel <= DIAL_SWEEP_DEG) rel / DIAL_SWEEP_DEG else null
}

/** "72" or "71.5" — whole numbers stay whole. Shared by the dial and the card subtitle. */
fun fmtTemp(t: Double): String = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)

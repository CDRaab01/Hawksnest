package com.hawksnest.core.logic

import com.hawksnest.core.ha.HistoryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure axis math for the entity-history chart — no Compose, so the scale/tick/format decisions are
 * unit-tested in isolation. The 1:1 port of the web `src/lib/chart.ts`; keep the two in step.
 *
 * The chart plots **against time**, not against sample index: HA's history is event-driven, so
 * samples are unevenly spaced and an index axis would put a quiet hour and a busy minute the same
 * distance apart — which is exactly the lie a labelled time axis must not tell.
 */

/** A history series reduced to plottable numbers. */
data class ChartSeries(
    /** y value per sample, in sample order. */
    val values: List<Float>,
    /** epoch-ms per sample, in sample order. */
    val times: List<Long>,
    /** True when every sample parsed as a finite number (sensor/climate/level). */
    val numeric: Boolean,
    /** Distinct state labels for a discrete series — level `i` is `labels[i]`. Empty when numeric. */
    val labels: List<String>,
)

data class AxisTick(val value: Float, val label: String)

/** The value (y) axis: the domain the plot maps onto plus its labelled ticks. */
data class ValueAxis(val lo: Float, val hi: Float, val ticks: List<AxisTick>)

data class TimeTick(val t: Long, val label: String)

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * Reduce a state series to plottable numbers: an all-numeric series plots its own values; a
 * discrete series (lock/binary/cover/…) maps each distinct state to an evenly-spaced level so
 * on/off/open/locked still draws a readable step.
 */
fun chartSeries(points: List<HistoryPoint>): ChartSeries {
    val numbers = points.map { it.state.toFloatOrNull() }
    val numeric = points.isNotEmpty() && numbers.all { it != null && it.isFinite() }
    if (numeric) {
        return ChartSeries(numbers.map { it!! }, points.map { it.t }, true, emptyList())
    }
    val labels = points.map { it.state }.distinct()
    val index = labels.withIndex().associate { (i, s) -> s to i.toFloat() }
    return ChartSeries(
        values = points.map { index[it.state] ?: 0f },
        times = points.map { it.t },
        numeric = false,
        labels = labels,
    )
}

/**
 * The nearest "human" step at or above [raw] — 1, 2, 5 or 10 times a power of ten. Axis labels
 * people can read at a glance are the whole point of the axis.
 */
fun niceStep(raw: Double): Double {
    if (!raw.isFinite() || raw <= 0.0) return 1.0
    val exp = floor(log10(raw))
    val pow = 10.0.pow(exp)
    val f = raw / pow
    val nf = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return nf * pow
}

/** Decimal places worth printing for values spaced [step] apart (capped at 3). */
fun stepDecimals(step: Double): Int {
    if (!step.isFinite() || step <= 0.0) return 0
    return min(3, max(0, -floor(log10(step)).toInt()))
}

/** Prettify a raw HA state for a discrete axis label ("not_home" → "Not home"). */
private fun prettyState(state: String): String =
    state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/** How many discrete levels still get one label each before the axis thins out. */
private const val MAX_DISCRETE_TICKS = 5

/**
 * The value axis for a series. Numeric series get a rounded domain with evenly spaced ticks (so the
 * labels sit at even fractions of the plot height, which is what lets the renderer place them
 * without measuring text); discrete series get one tick per state, thinned to the first and last
 * when there are many.
 */
fun valueAxis(series: ChartSeries, unit: String? = null, targetTicks: Int = 3): ValueAxis {
    if (!series.numeric) {
        val levels = series.labels
        val hi = max(1, levels.size - 1).toFloat()
        val ticks = if (levels.size <= MAX_DISCRETE_TICKS) {
            levels.mapIndexed { i, s -> AxisTick(i.toFloat(), prettyState(s)) }
        } else {
            listOf(
                AxisTick(0f, prettyState(levels.first())),
                AxisTick((levels.size - 1).toFloat(), prettyState(levels.last())),
            )
        }
        return ValueAxis(0f, hi, ticks)
    }

    val finite = series.values.filter { it.isFinite() }
    val minV = (finite.minOrNull() ?: 0f).toDouble()
    val maxV = (finite.maxOrNull() ?: 1f).toDouble()
    // A flat series still deserves a readable axis — spread a band around it.
    val span = (maxV - minV).takeIf { it > 0.0 } ?: abs(maxV).takeIf { it > 0.0 } ?: 1.0
    val step = niceStep(span / max(1, targetTicks - 1))
    val lo = floor(minV / step) * step
    // A series that sits exactly on a step boundary (a flat one, or 20–20) would collapse lo and hi
    // onto each other — give it one step of headroom.
    val ceil = kotlin.math.ceil(maxV / step) * step
    val hi = if (ceil > lo) ceil else lo + step
    val decimals = stepDecimals(step)
    val suffix = unit ?: ""

    // Count the ticks rather than accumulating floats — 0.1-sized steps never land exactly on `hi`.
    val count = ((hi - lo) / step).roundToInt()
    val ticks = (0..count).map { i ->
        val value = lo + step * i
        AxisTick(value.toFloat(), String.format(Locale.US, "%.${decimals}f", value) + suffix)
    }
    return ValueAxis(lo.toFloat(), hi.toFloat(), ticks)
}

/** Time steps the axis is allowed to land on, coarsest-last. */
private val TIME_STEPS = listOf(
    5 * MINUTE,
    15 * MINUTE,
    30 * MINUTE,
    HOUR,
    2 * HOUR,
    3 * HOUR,
    6 * HOUR,
    12 * HOUR,
    DAY,
    2 * DAY,
    7 * DAY,
    14 * DAY,
)

/** The first tick at or after [t] on a [step] grid, aligned to local time. */
private fun alignUp(t: Long, step: Long): Long {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(t).atZone(zone)
    val base =
        if (step >= DAY) at.toLocalDate().atStartOfDay(zone) else at.truncatedTo(ChronoUnit.HOURS)
    var x = base.toInstant().toEpochMilli()
    while (x < t) x += step
    return x
}

/**
 * Round clock/date labels across `[t0, t1]`, spaced on a human step (hours or days, never "every 47
 * minutes"). Returns an empty list when the span is empty.
 */
fun timeAxis(t0: Long, t1: Long, target: Int = 4): List<TimeTick> {
    val span = t1 - t0
    if (span <= 0) return emptyList()
    val raw = span / max(1, target)
    val step = TIME_STEPS.firstOrNull { it >= raw } ?: TIME_STEPS.last()
    val ticks = mutableListOf<TimeTick>()
    var t = alignUp(t0, step)
    while (t <= t1) {
        ticks += TimeTick(t, formatAxisTime(t, span))
        t += step
    }
    return ticks
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * A tick's label: clock time for spans a person still thinks about in hours, calendar date beyond
 * that. 24-hour so the web and Android axes read alike.
 */
fun formatAxisTime(t: Long, spanMs: Long): String {
    val at = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
    return if (spanMs > 3 * DAY) DATE_FORMAT.format(at) else CLOCK_FORMAT.format(at)
}

/** Where [t] falls across `[t0, t1]`, 0–1. A zero span pins everything to 0. */
fun timeFraction(t: Long, t0: Long, t1: Long): Float {
    val span = t1 - t0
    if (span <= 0) return 0f
    return ((t - t0).toFloat() / span.toFloat()).coerceIn(0f, 1f)
}

/** Where [v] falls across `[lo, hi]`, 0–1 measured from the axis floor. */
fun valueFraction(v: Float, lo: Float, hi: Float): Float {
    val span = hi - lo
    if (span <= 0f) return 0f
    return ((v - lo) / span).coerceIn(0f, 1f)
}

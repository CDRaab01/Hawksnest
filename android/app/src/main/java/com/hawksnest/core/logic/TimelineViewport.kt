package com.hawksnest.core.logic

/**
 * Pure math for the Ring-style camera timeline: a center-anchored, zoomable +
 * pannable viewport over a fixed data window. Dependency-free (no Compose) so it
 * unit-tests directly. 1:1 port of the web `src/lib/timelineViewport.ts`.
 *
 * A viewport is `(centerMs, msPerPx)` for a track of pixel width `width`:
 *  - `centerMs` is the time under the fixed center playhead (the scrub position),
 *  - `msPerPx` is the zoom scale (ms per pixel; smaller = more zoomed in).
 */

const val MINUTE_MS = 60_000L
const val HOUR_MS = 3_600_000L

/** Most zoomed-IN visible span (Ring-style ~10 min). */
const val MIN_SPAN_MS = 10 * MINUTE_MS

/** Most zoomed-OUT visible span ceiling (the rolling data window is usually 24h). */
const val MAX_SPAN_MS = 24 * HOUR_MS

/** The fixed data window the viewport lives inside (epoch ms). */
data class TimeWindow(val startMs: Long, val endMs: Long)

data class Viewport(val centerMs: Long, val msPerPx: Double)

/** Visible span (ms) for a track of width `width` px. */
fun visibleSpanMs(vp: Viewport, width: Float): Double = vp.msPerPx * width

/** Map a time to its x pixel (center-anchored: centerMs → width/2). */
fun timeToX(t: Long, vp: Viewport, width: Float): Float =
    width / 2f + ((t - vp.centerMs) / vp.msPerPx).toFloat()

/** Map an x pixel back to a time. Inverse of [timeToX]. */
fun xToTime(x: Float, vp: Viewport, width: Float): Long =
    vp.centerMs + ((x - width / 2f) * vp.msPerPx).toLong()

/** Largest visible span allowed: min(24h, the data window itself). */
fun maxSpanMs(window: TimeWindow): Long =
    minOf(MAX_SPAN_MS, maxOf(1L, window.endMs - window.startMs))

/** Smallest visible span allowed (collapses if the window is itself < MIN_SPAN). */
fun minSpanMs(window: TimeWindow): Long = minOf(MIN_SPAN_MS, maxSpanMs(window))

/** Clamp the zoom so the visible span stays within [minSpan, maxSpan]. */
fun clampMsPerPx(msPerPx: Double, width: Float, window: TimeWindow): Double {
    if (width <= 0f) return msPerPx
    val lo = minSpanMs(window).toDouble() / width
    val hi = maxSpanMs(window).toDouble() / width
    return msPerPx.coerceIn(lo, hi)
}

/**
 * Clamp the center so the visible window stays inside the data window. When the
 * data window is narrower than the visible span, pin to the window midpoint.
 */
fun clampCenter(centerMs: Long, width: Float, msPerPx: Double, window: TimeWindow): Long {
    val half = msPerPx * width / 2.0
    val lo = window.startMs + half
    val hi = window.endMs - half
    if (lo > hi) return (window.startMs + window.endMs) / 2
    return centerMs.toDouble().coerceIn(lo, hi).toLong()
}

/** Normalize a viewport: clamp zoom first, then recenter within bounds. */
fun clampViewport(vp: Viewport, width: Float, window: TimeWindow): Viewport {
    val msPerPx = clampMsPerPx(vp.msPerPx, width, window)
    val centerMs = clampCenter(vp.centerMs, width, msPerPx, window)
    return Viewport(centerMs, msPerPx)
}

/** Pan by a pixel delta (drag dx). Drag the strip right (dx > 0) → go back in time. */
fun pan(vp: Viewport, dxPx: Float, width: Float, window: TimeWindow): Viewport {
    val newCenter = (vp.centerMs - dxPx * vp.msPerPx).toLong()
    return clampViewport(vp.copy(centerMs = newCenter), width, window)
}

/** Zoom about the center by [factor] (> 1 zooms in; centerMs unchanged). */
fun zoom(vp: Viewport, factor: Float, width: Float, window: TimeWindow): Viewport {
    val f = if (factor > 0f) factor else 1f
    return clampViewport(vp.copy(msPerPx = vp.msPerPx / f), width, window)
}

/** Build a viewport showing [span] ms centered at [centerMs], clamped to the window. */
fun viewportForSpan(centerMs: Long, span: Long, width: Float, window: TimeWindow): Viewport {
    val msPerPx = if (width > 0f) span.toDouble() / width else 1.0
    return clampViewport(Viewport(centerMs, msPerPx), width, window)
}

/** The visible [startMs, endMs] of the viewport. */
fun visibleRange(vp: Viewport, width: Float): TimeWindow {
    val half = (vp.msPerPx * width / 2.0).toLong()
    return TimeWindow(vp.centerMs - half, vp.centerMs + half)
}

// "Nice" tick intervals, coarsest-last; picked so ~targetTicks land on screen.
private val TICK_STEPS = longArrayOf(
    5 * MINUTE_MS,
    15 * MINUTE_MS,
    30 * MINUTE_MS,
    HOUR_MS,
    3 * HOUR_MS,
    6 * HOUR_MS,
    12 * HOUR_MS,
)

/** Pick a tick interval (ms) for the given visible span. */
fun tickIntervalMs(spanMs: Long, targetTicks: Int = 6): Long {
    val ideal = spanMs / maxOf(1, targetTicks)
    for (step in TICK_STEPS) if (step >= ideal) return step
    return TICK_STEPS.last()
}

/** Tick times (epoch ms) falling within the viewport's visible range. */
fun ticks(vp: Viewport, width: Float): List<Long> {
    val range = visibleRange(vp, width)
    val interval = tickIntervalMs(range.endMs - range.startMs)
    // ceil(start / interval) * interval for positive epoch values.
    val first = ((range.startMs + interval - 1) / interval) * interval
    val out = ArrayList<Long>()
    var t = first
    while (t <= range.endMs) {
        out.add(t)
        t += interval
    }
    return out
}

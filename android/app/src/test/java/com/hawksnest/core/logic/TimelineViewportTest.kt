package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ports `timelineViewport.test.ts` 1:1. */
class TimelineViewportTest {

    private val w = 1000f
    private val end = 1_700_000_000_000L
    private val window = TimeWindow(end - 24 * HOUR_MS, end)

    // A 1h-visible viewport centered 2h before the live edge.
    private fun vp(): Viewport = viewportForSpan(end - 2 * HOUR_MS, HOUR_MS, w, window)

    private fun near(a: Double, b: Double, tol: Double = 1.0) =
        assertTrue(kotlin.math.abs(a - b) <= tol, "expected ~$b but was $a")

    @Test
    fun `center-anchored: centerMs maps to the track middle and back`() {
        val v = vp()
        near(timeToX(v.centerMs, v, w).toDouble(), (w / 2f).toDouble())
        assertEquals(v.centerMs, xToTime(w / 2f, v, w))
    }

    @Test
    fun `round-trips time to x`() {
        val v = vp()
        for (x in listOf(0f, 250f, 500f, 750f, 1000f)) {
            near(timeToX(xToTime(x, v, w), v, w).toDouble(), x.toDouble(), tol = 1.0)
        }
    }

    @Test
    fun `visibleSpanMs reflects the zoom`() {
        near(visibleSpanMs(vp(), w), HOUR_MS.toDouble(), tol = 1000.0)
    }

    @Test
    fun `drag right goes back in time; drag left goes forward`() {
        val v = vp()
        assertTrue(pan(v, 100f, w, window).centerMs < v.centerMs)
        assertTrue(pan(v, -100f, w, window).centerMs > v.centerMs)
    }

    @Test
    fun `cannot pan past the live edge`() {
        val v = vp()
        val far = pan(v, -10_000f, w, window)
        val half = (visibleSpanMs(far, w) / 2).toLong()
        assertTrue(far.centerMs <= window.endMs - half + 1)
    }

    @Test
    fun `cannot pan past the window start`() {
        val v = vp()
        val far = pan(v, 10_000f, w, window)
        val half = (visibleSpanMs(far, w) / 2).toLong()
        assertTrue(far.centerMs >= window.startMs + half - 1)
    }

    @Test
    fun `zooming in shrinks the visible span about the center`() {
        val v = vp()
        val z = zoom(v, 2f, w, window)
        assertEquals(v.centerMs, z.centerMs)
        assertTrue(visibleSpanMs(z, w) < visibleSpanMs(v, w))
    }

    @Test
    fun `clamps zoom-in at MIN_SPAN and zoom-out at the window span`() {
        val v = vp()
        val tightest = zoom(v, 1e6f, w, window)
        near(visibleSpanMs(tightest, w), MIN_SPAN_MS.toDouble(), tol = 1000.0)
        val widest = zoom(v, 1e-6f, w, window)
        near(visibleSpanMs(widest, w), (24 * HOUR_MS).toDouble(), tol = 1000.0)
    }

    @Test
    fun `pins center to the midpoint when the window is narrower than the span`() {
        val tiny = TimeWindow(0, 5 * MINUTE_MS)
        val c = clampCenter(0, w, (10 * MINUTE_MS).toDouble() / w, tiny)
        assertEquals(2L * MINUTE_MS + MINUTE_MS / 2, c) // 2.5 min
    }

    @Test
    fun `never lets the visible span exceed a sub-10-min window`() {
        val tiny = TimeWindow(0, 5 * MINUTE_MS)
        val mpp = clampMsPerPx(HOUR_MS.toDouble() / w, w, tiny)
        assertTrue(mpp * w <= 5 * MINUTE_MS + 1)
    }

    @Test
    fun `picks a coarser interval as the span widens`() {
        assertTrue(tickIntervalMs(HOUR_MS) < tickIntervalMs(12 * HOUR_MS))
    }

    @Test
    fun `returns tick times within the visible range on the interval grid`() {
        val v = vp()
        val t = ticks(v, w)
        val range = visibleRange(v, w)
        val interval = tickIntervalMs(range.endMs - range.startMs)
        assertTrue(t.isNotEmpty())
        for (x in t) {
            assertTrue(x in range.startMs..range.endMs)
            assertEquals(0L, x % interval)
        }
    }
}

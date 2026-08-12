package com.hawksnest.core.logic

import com.hawksnest.core.ha.HistoryPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 1:1 twin of the web `src/lib/__tests__/chart.test.ts` — keep the two in step. */
class ChartTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    /** 2026-08-12T00:00:00Z, a stable anchor so tick math never depends on "now". */
    private val t0 = 1_786_492_800_000L

    private fun series(states: List<String>, stepMs: Long = hour): List<HistoryPoint> =
        states.mapIndexed { i, s -> HistoryPoint(t0 + i * stepMs, s) }

    @Test
    fun `chartSeries plots an all-numeric series as its own values`() {
        val s = chartSeries(series(listOf("10", "20.5", "15")))
        assertTrue(s.numeric)
        assertEquals(listOf(10f, 20.5f, 15f), s.values)
        assertEquals(emptyList(), s.labels)
        assertEquals(listOf(t0, t0 + hour, t0 + 2 * hour), s.times)
    }

    @Test
    fun `chartSeries maps a discrete series onto evenly-spaced levels`() {
        val s = chartSeries(series(listOf("locked", "unlocked", "locked", "jammed")))
        assertTrue(!s.numeric)
        assertEquals(listOf("locked", "unlocked", "jammed"), s.labels)
        assertEquals(listOf(0f, 1f, 0f, 2f), s.values)
    }

    @Test
    fun `chartSeries treats a series with any non-number as discrete`() {
        val s = chartSeries(series(listOf("10", "unavailable", "12")))
        assertTrue(!s.numeric)
        assertEquals(listOf("10", "unavailable", "12"), s.labels)
    }

    @Test
    fun `chartSeries is empty-safe`() {
        val s = chartSeries(emptyList())
        assertTrue(!s.numeric)
        assertEquals(emptyList(), s.values)
    }

    @Test
    fun `niceStep rounds up to 1-2-5 times a power of ten`() {
        assertEquals(1.0, niceStep(0.9))
        assertEquals(2.0, niceStep(1.7))
        assertEquals(5.0, niceStep(4.0))
        assertEquals(10.0, niceStep(7.0))
        assertEquals(50.0, niceStep(23.0))
        assertTrue(abs(niceStep(0.03) - 0.05) < 1e-9)
    }

    @Test
    fun `niceStep never returns zero or NaN for degenerate input`() {
        assertEquals(1.0, niceStep(0.0))
        assertEquals(1.0, niceStep(-5.0))
        assertEquals(1.0, niceStep(Double.NaN))
    }

    @Test
    fun `stepDecimals prints only the digits the step can resolve`() {
        assertEquals(0, stepDecimals(10.0))
        assertEquals(0, stepDecimals(1.0))
        assertEquals(1, stepDecimals(0.5))
        assertEquals(2, stepDecimals(0.05))
        assertEquals(3, stepDecimals(0.000001)) // capped
    }

    @Test
    fun `valueAxis gives a numeric series a rounded domain with evenly-spaced ticks`() {
        val axis = valueAxis(chartSeries(series(listOf("12", "47", "31"))))
        assertTrue(axis.lo <= 12f)
        assertTrue(axis.hi >= 47f)
        val values = axis.ticks.map { it.value }
        val gaps = values.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { abs(it - gaps.first()) < 1e-3 })
        assertEquals(axis.lo, values.first())
        assertEquals(axis.hi, values.last())
    }

    @Test
    fun `valueAxis appends the unit to numeric tick labels`() {
        val axis = valueAxis(chartSeries(series(listOf("10", "30"))), "°F")
        assertTrue(axis.ticks.all { it.label.endsWith("°F") })
    }

    @Test
    fun `valueAxis still spans a readable band for a flat series`() {
        val axis = valueAxis(chartSeries(series(listOf("20", "20", "20"))))
        assertTrue(axis.hi > axis.lo)
        assertTrue(axis.ticks.size >= 2)
    }

    @Test
    fun `valueAxis names each state of a discrete series`() {
        val axis = valueAxis(chartSeries(series(listOf("locked", "unlocked"))))
        assertEquals(0f, axis.lo)
        assertEquals(1f, axis.hi)
        assertEquals(listOf("Locked", "Unlocked"), axis.ticks.map { it.label })
    }

    @Test
    fun `valueAxis thins a many-state axis to its ends`() {
        val axis = valueAxis(chartSeries(series(listOf("a", "b", "c", "d", "e", "f", "g"))))
        assertEquals(2, axis.ticks.size)
        assertEquals("A", axis.ticks.first().label)
        assertEquals("G", axis.ticks.last().label)
    }

    @Test
    fun `valueAxis prettifies underscored states`() {
        val axis = valueAxis(chartSeries(series(listOf("not_home", "home"))))
        assertEquals("Not home", axis.ticks.first().label)
    }

    @Test
    fun `timeAxis spaces ticks on a human step inside the range`() {
        val ticks = timeAxis(t0, t0 + 24 * hour)
        assertTrue(ticks.size >= 3)
        assertTrue(ticks.all { it.t in t0..(t0 + 24 * hour) })
        val gaps = ticks.map { it.t }.zipWithNext { a, b -> b - a }
        assertEquals(setOf(6 * hour), gaps.toSet())
    }

    @Test
    fun `timeAxis uses day steps and date labels over a 30-day range`() {
        val ticks = timeAxis(t0, t0 + 30 * day)
        assertTrue(ticks.size >= 3)
        assertTrue(ticks.all { !it.label.contains(":") })
    }

    @Test
    fun `timeAxis uses clock labels over a 6-hour range`() {
        val ticks = timeAxis(t0, t0 + 6 * hour)
        assertTrue(ticks.all { Regex("""^\d{2}:\d{2}$""").matches(it.label) })
    }

    @Test
    fun `timeAxis returns nothing for an empty or inverted span`() {
        assertEquals(emptyList(), timeAxis(t0, t0))
        assertEquals(emptyList(), timeAxis(t0, t0 - hour))
    }

    @Test
    fun `formatAxisTime switches from clock to calendar past three days`() {
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(formatAxisTime(t0, 6 * hour)))
        assertTrue(!formatAxisTime(t0, 30 * day).contains(":"))
    }

    @Test
    fun `timeFraction places a time across the span and clamps outside it`() {
        assertTrue(abs(timeFraction(t0 + hour, t0, t0 + 4 * hour) - 0.25f) < 1e-6)
        assertEquals(0f, timeFraction(t0 - hour, t0, t0 + 4 * hour))
        assertEquals(1f, timeFraction(t0 + 9 * hour, t0, t0 + 4 * hour))
        assertEquals(0f, timeFraction(t0, t0, t0))
    }

    @Test
    fun `valueFraction places a value across the domain and clamps outside it`() {
        assertTrue(abs(valueFraction(25f, 0f, 100f) - 0.25f) < 1e-6)
        assertEquals(0f, valueFraction(-5f, 0f, 100f))
        assertEquals(0f, valueFraction(5f, 10f, 10f))
    }
}

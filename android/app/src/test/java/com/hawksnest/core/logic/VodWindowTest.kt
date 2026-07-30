package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `src/lib/__tests__/vodWindow.test.ts`. The two platforms must page identically —
 * a divergence here means the same scrub lands on different media on phone and browser.
 */
class VodWindowTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun `retentionRange spans retentionDays back from now`() {
        val now = 1_800_000_000_000L
        assertEquals(TimeRange(now - 3 * day, now), retentionRange(now, 3.0))
    }

    @Test
    fun `retentionRange falls back when Frigate reports nothing usable`() {
        val now = 1_800_000_000_000L
        val expected = now - (DEFAULT_RETENTION_DAYS * day).toLong()
        for (bad in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(expected, retentionRange(now, bad).startMs)
        }
    }

    @Test
    fun `page never exceeds the segment-ceiling budget`() {
        val bounds = TimeRange(0, 100 * day)
        val page = vodPageFor(50 * day + 37 * 60_000L, bounds)
        assertTrue(page.endMs - page.startMs <= VOD_PAGE_MS)
    }

    @Test
    fun `page is grid-aligned so scrubbing within it keeps the same range`() {
        // The property that stops a drag re-requesting the manifest on every move.
        val bounds = TimeRange(0, 100 * day)
        val base = 50 * day
        val first = vodPageFor(base, bounds)
        for (offset in listOf(1L, 60_000L, VOD_PAGE_MS - 1)) {
            assertEquals(first, vodPageFor(base + offset, bounds))
        }
    }

    @Test
    fun `crossing the boundary moves to the next page`() {
        val bounds = TimeRange(0, 100 * day)
        val base = (50 * day / VOD_PAGE_MS) * VOD_PAGE_MS
        assertNotEquals(vodPageFor(base, bounds), vodPageFor(base + VOD_PAGE_MS, bounds))
    }

    @Test
    fun `page never starts before recordings exist`() {
        val bounds = TimeRange(10 * day + 1234, 11 * day)
        assertTrue(vodPageFor(bounds.startMs, bounds).startMs >= bounds.startMs)
    }

    @Test
    fun `page never ends in the future`() {
        val now = 10 * day + 12345
        val bounds = TimeRange(now - 3 * day, now)
        assertTrue(vodPageFor(now, bounds).endMs <= now)
    }

    @Test
    fun `degenerate bounds do not invert the range`() {
        val bounds = TimeRange(5 * day, 5 * day)
        val page = vodPageFor(5 * day, bounds)
        assertTrue(page.endMs >= page.startMs)
    }

    @Test
    fun `needsNewPage is true with nothing loaded`() {
        assertTrue(needsNewPage(null, 5 * day, TimeRange(0, 100 * day)))
    }

    @Test
    fun `needsNewPage is false while scrubbing inside the loaded page`() {
        val bounds = TimeRange(0, 100 * day)
        val head = 50 * day
        val loaded = vodPageFor(head, bounds)
        assertTrue(!needsNewPage(loaded, head + 60_000L, bounds))
    }

    @Test
    fun `needsNewPage is true once the playhead crosses into the next page`() {
        val bounds = TimeRange(0, 100 * day)
        val loaded = vodPageFor(50 * day, bounds)
        assertTrue(needsNewPage(loaded, loaded.endMs + 1, bounds))
    }

    @Test
    fun `vodPositionMsInPage offsets from the page start, not the timeline start`() {
        val pageStart = 50 * day
        assertEquals(90_000L, vodPositionMsInPage(pageStart + 90_000L, pageStart))
    }

    @Test
    fun `vodPositionMsInPage never returns a negative seek`() {
        val pageStart = 50 * day
        assertEquals(0L, vodPositionMsInPage(pageStart - 5000L, pageStart))
    }
}

package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // vodRangeFor — mirrors the `vodRangeFor` describe in vodWindow.test.ts.
    private val rangeBounds = TimeRange(0, 100 * day)
    // 37 minutes into a page boundary (50 days is a multiple of the 2h grid).
    private val rangeHead = 50 * day + 37 * 60_000L

    @Test
    fun `vodRangeFor is the plain grid page while the lane has not answered`() {
        assertEquals(vodPageFor(rangeHead, rangeBounds), vodRangeFor(rangeHead, rangeBounds, emptyList()))
    }

    @Test
    fun `vodRangeFor is identical to the grid page on a 24-7 camera whose span covers it`() {
        val solid = listOf(FootageSpan(0, 100 * day, playable = true))
        assertEquals(vodPageFor(rangeHead, rangeBounds), vodRangeFor(rangeHead, rangeBounds, solid))
    }

    @Test
    fun `vodRangeFor clamps the page to the footage island under the playhead`() {
        val island = FootageSpan(rangeHead - 2 * 60_000L, rangeHead + 3 * 60_000L, playable = true)
        assertEquals(
            TimeRange(island.startMs, island.endMs),
            vodRangeFor(rangeHead, rangeBounds, listOf(island)),
        )
    }

    @Test
    fun `vodRangeFor is null in a known gap and for an unplayable span`() {
        val elsewhere = listOf(FootageSpan(rangeHead + hour, rangeHead + 2 * hour, playable = true))
        assertNull(vodRangeFor(rangeHead, rangeBounds, elsewhere))
        val unplayable = listOf(FootageSpan(0, 100 * day, playable = false))
        assertNull(vodRangeFor(rangeHead, rangeBounds, unplayable))
    }

    @Test
    fun `vodRangeFor keeps one range for every playhead inside the same island and page`() {
        val island = listOf(FootageSpan(rangeHead - 10 * 60_000L, rangeHead + 10 * 60_000L, playable = true))
        assertEquals(
            vodRangeFor(rangeHead, rangeBounds, island),
            vodRangeFor(rangeHead + 60_000L, rangeBounds, island),
        )
    }

    // ---- regression: wall-clock and playlist time must not drift (nursery_high, 2026-10-01) ----
    // A stuck Frigate record process left ~2 s holes between every ~10 s segment on one camera.
    // Every hole was under the lane's 15 s coalescing tolerance, which was ALSO the parse default,
    // so the lane reported one unbroken span across the whole 2-hour page. vodRangeFor therefore
    // narrowed nothing, and vodPositionMsInPage - which assumes the page is solid footage - seeked
    // by wall-clock offset into a playlist that is only the surviving footage concatenated. A
    // scrub to 7:13:14 PM played 7:23:28 PM, and a clip exported from that playhead was cut at the
    // marked time rather than the time on screen, so it came back "the wrong time".

    private val pageStart = 1_790_805_600_000L // 18:00:00 local, a 2h grid boundary
    private val driftBounds = TimeRange(pageStart, pageStart + 4 * hour)
    private val target = 1_790_809_994_000L // 19:13:14 local - what the user marked

    /** The real shape: 10 s of footage every 12 s of wall clock. */
    private val gappy = (0 until 600).map {
        FootageSpan(pageStart + it * 12_000L, pageStart + it * 12_000L + 10_000L, playable = true)
    }

    @Test
    fun `narrows the page to the contiguous segment under the playhead`() {
        val range = vodRangeFor(target, driftBounds, gappy)
        assertTrue(range != null)
        assertTrue(range!!.endMs - range.startMs <= 10_000L)
        assertTrue(range.startMs <= target && range.endMs > target)
    }

    @Test
    fun `keeps the seek honest - offset into the range is offset into the media`() {
        val range = vodRangeFor(target, driftBounds, gappy)!!
        // The invariant vodRangeFor exists to guarantee: because the range is contiguous footage,
        // wall-clock offset from its start IS the playlist position.
        val pos = vodPositionMsInPage(target, range.startMs)
        assertTrue(pos <= 10_000L)
        assertEquals(target - range.startMs, pos)
    }

    @Test
    fun `would have drifted ~10 minutes with the page unnarrowed`() {
        // Pin the old failure so nobody restores the single-tolerance shortcut by accident.
        val naive = vodPositionMsInPage(target, pageStart)
        val footageBefore = gappy.filter { it.startMs < target }
            .sumOf { minOf(it.endMs, target) - it.startMs }
        assertEquals(4_394_000L, naive)
        assertTrue(naive - footageBefore > 600_000L)
    }

    @Test
    fun `a scrub into one of the holes is an honest no-recording not a wrong frame`() {
        val inHole = pageStart + 11_000L // between segment 0 (ends +10s) and segment 1 (+12s)
        assertNull(vodRangeFor(inHole, driftBounds, gappy))
    }
}

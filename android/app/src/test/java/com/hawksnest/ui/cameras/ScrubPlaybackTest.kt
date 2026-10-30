package com.hawksnest.ui.cameras

import com.hawksnest.core.logic.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubPlaybackTest {
    private val hour = 3_600_000L

    @Test
    fun `a settled seek never waits`() {
        assertEquals(0L, scrubSeekDelayMs(nowMs = 1_000, lastSeekAtMs = 990, scrubbing = false))
    }

    @Test
    fun `the first drag seek is immediate`() {
        assertEquals(0L, scrubSeekDelayMs(nowMs = 1_000, lastSeekAtMs = null, scrubbing = true))
    }

    @Test
    fun `drag seeks are spaced by the interval`() {
        assertEquals(
            SCRUB_SEEK_INTERVAL_MS - 40,
            scrubSeekDelayMs(nowMs = 1_040, lastSeekAtMs = 1_000, scrubbing = true),
        )
        assertEquals(0L, scrubSeekDelayMs(nowMs = 1_000 + SCRUB_SEEK_INTERVAL_MS, lastSeekAtMs = 1_000, scrubbing = true))
        assertEquals(0L, scrubSeekDelayMs(nowMs = 9_000, lastSeekAtMs = 1_000, scrubbing = true))
    }

    @Test
    fun `a clock that went backwards never waits longer than one interval`() {
        assertEquals(SCRUB_SEEK_INTERVAL_MS, scrubSeekDelayMs(nowMs = 500, lastSeekAtMs = 1_000, scrubbing = true))
    }

    @Test
    fun `the page holds where the drag began and follows the playhead otherwise`() {
        val start = 10 * hour
        val now = 3 * hour
        assertEquals(start, pageHeadMs(headMs = now, scrubbing = true, dragStartHeadMs = start))
        assertEquals(now, pageHeadMs(headMs = now, scrubbing = false, dragStartHeadMs = start))
        assertEquals(now, pageHeadMs(headMs = now, scrubbing = true, dragStartHeadMs = null))
    }

    @Test
    fun `seek offset is relative to the page and clamps to its edges`() {
        val page = TimeRange(10 * hour, 12 * hour)
        assertEquals(30 * 60_000L, seekOffsetInPage(10 * hour + 30 * 60_000L, page))
        assertEquals(0L, seekOffsetInPage(9 * hour, page))
        assertEquals(2 * hour, seekOffsetInPage(13 * hour, page))
    }

    @Test
    fun `a degenerate page never yields a negative offset`() {
        val page = TimeRange(10 * hour, 10 * hour)
        assertEquals(0L, seekOffsetInPage(11 * hour, page))
        assertEquals(0L, seekOffsetInPage(9 * hour, page))
    }

    @Test
    fun `the vod cache key drops authSig so a re-signed page reuses its bytes`() {
        val a = "https://h.ts.net:8443/api/frigate/vod/garage/start/1/end/2/seg-3-v1-a1.m4s?authSig=AAA"
        val b = "https://h.ts.net:8443/api/frigate/vod/garage/start/1/end/2/seg-3-v1-a1.m4s?authSig=BBB"
        assertEquals(vodCacheKey(a), vodCacheKey(b))
        assertEquals("https://h.ts.net:8443/api/frigate/vod/garage/start/1/end/2/seg-3-v1-a1.m4s", vodCacheKey(a))
    }

    @Test
    fun `playlists bypass the cache and media does not`() {
        assertTrue(isPlaylistRequest("https://h/api/frigate/vod/c/start/1/end/2/master.m3u8?authSig=x"))
        assertTrue(isPlaylistRequest("https://h/api/frigate/vod/c/start/1/end/2/index-v1-a1.m3u8"))
        assertFalse(isPlaylistRequest("https://h/api/frigate/vod/c/start/1/end/2/init-v1-a1.mp4?authSig=x"))
        assertFalse(isPlaylistRequest("https://h/api/frigate/vod/c/start/1/end/2/seg-1-v1-a1.m4s?authSig=x"))
    }
}

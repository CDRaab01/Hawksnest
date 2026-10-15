package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class LivePosterTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `a frame grabbed moments ago wins - it paints with no fetch`() {
        assertEquals(LivePosterSource.FRAME, livePosterSource(now - 5_000, now, sleeps = false, hasSnapshot = true))
        assertEquals(
            LivePosterSource.FRAME,
            livePosterSource(now - POSTER_FRAME_FRESH_MS, now, sleeps = false, hasSnapshot = true),
        )
    }

    @Test
    fun `an old frame loses to a 24-7 camera's current snapshot`() {
        assertEquals(
            LivePosterSource.SNAPSHOT,
            livePosterSource(now - POSTER_FRAME_FRESH_MS - 1, now, sleeps = false, hasSnapshot = true),
        )
        assertEquals(LivePosterSource.SNAPSHOT, livePosterSource(null, now, sleeps = false, hasSnapshot = true))
    }

    @Test
    fun `a parked battery camera's snapshot is never shown - its grey error image is not the camera`() {
        assertEquals(LivePosterSource.NONE, livePosterSource(null, now, sleeps = true, hasSnapshot = true))
        // Its last live frame is the truthful picture, however old.
        assertEquals(LivePosterSource.FRAME, livePosterSource(now - 3_600_000, now, sleeps = true, hasSnapshot = true))
    }

    @Test
    fun `with no snapshot an old frame beats an empty box, and nothing at all means no poster`() {
        assertEquals(LivePosterSource.FRAME, livePosterSource(now - 3_600_000, now, sleeps = false, hasSnapshot = false))
        assertEquals(LivePosterSource.NONE, livePosterSource(null, now, sleeps = false, hasSnapshot = false))
    }
}

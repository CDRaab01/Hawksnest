package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipTest {

    @Test
    fun `enters PiP only for an open live session`() {
        assertTrue(shouldEnterPip(sessionOpen = true, isLive = true))
        assertFalse(shouldEnterPip(sessionOpen = true, isLive = false))
        assertFalse(shouldEnterPip(sessionOpen = false, isLive = true))
        assertFalse(shouldEnterPip(sessionOpen = false, isLive = false))
    }

    @Test
    fun `unknown or degenerate size falls back to 16x9`() {
        assertEquals(16 to 9, pipAspect(null, null))
        assertEquals(16 to 9, pipAspect(1920, null))
        assertEquals(16 to 9, pipAspect(null, 1080))
        assertEquals(16 to 9, pipAspect(0, 1080))
        assertEquals(16 to 9, pipAspect(1920, 0))
        assertEquals(16 to 9, pipAspect(-1920, 1080))
    }

    @Test
    fun `normal aspects pass through untouched`() {
        assertEquals(1920 to 1080, pipAspect(1920, 1080))
        assertEquals(640 to 480, pipAspect(640, 480))
        assertEquals(1080 to 1920, pipAspect(1080, 1920))
    }

    @Test
    fun `extreme wide clamps to the platform maximum`() {
        // 32:9 super-ultrawide is past Android's 2.39:1 limit.
        assertEquals(239 to 100, pipAspect(3200, 900))
    }

    @Test
    fun `extreme tall clamps to the platform minimum`() {
        assertEquals(100 to 239, pipAspect(900, 3200))
    }

    @Test
    fun `the platform limits themselves are allowed, just past them clamps`() {
        assertEquals(239 to 100, pipAspect(239, 100))
        assertEquals(100 to 239, pipAspect(100, 239))
        assertEquals(239 to 100, pipAspect(240, 100))
        assertEquals(100 to 239, pipAspect(100, 240))
    }
}

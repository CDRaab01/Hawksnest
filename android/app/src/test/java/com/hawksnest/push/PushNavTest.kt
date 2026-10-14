package com.hawksnest.push

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushNavTest {

    @Test
    fun `a camera request is acted on for a minute and dropped after`() {
        val t = CameraTarget("camera.front", atMs = 1_000_000L)
        assertTrue(t.isFresh(1_000_000L))
        assertTrue(t.isFresh(1_000_000L + CAMERA_TARGET_TTL_MS))
        assertFalse(t.isFresh(1_000_000L + CAMERA_TARGET_TTL_MS + 1))
    }
}

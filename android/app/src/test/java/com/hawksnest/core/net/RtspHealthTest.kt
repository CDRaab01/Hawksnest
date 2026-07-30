package com.hawksnest.core.net

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtspHealthTest {

    @BeforeTest
    @AfterTest
    fun reset() {
        RtspHealth.resetForTest()
    }

    @Test
    fun `unknown cameras are worth attempting`() {
        assertTrue(RtspHealth.maybeAvailable("big_room"))
    }

    @Test
    fun `a failure disables only that camera`() {
        RtspHealth.report("big_room", false)
        assertFalse(RtspHealth.maybeAvailable("big_room"))
        // The whole reason this breaker is per-camera and go2rtc's is global: each camera is its
        // own RTSP server, so one being off/rebooting/out of sessions predicts nothing about the
        // others. A global verdict would drop the entire fleet to go2rtc for the session.
        assertTrue(RtspHealth.maybeAvailable("kitchen"))
    }

    @Test
    fun `a later success clears the verdict`() {
        RtspHealth.report("big_room", false)
        assertFalse(RtspHealth.maybeAvailable("big_room"))
        RtspHealth.report("big_room", true)
        assertTrue(RtspHealth.maybeAvailable("big_room"))
    }
}

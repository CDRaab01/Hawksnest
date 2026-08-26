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


    @Test
    fun `a camera's verdict expires, so a reboot cannot demote it forever`() {
        var clock = 1_000L
        RtspHealth.setClockForTest { clock }

        RtspHealth.report("garage", false)
        assertFalse(RtspHealth.maybeAvailable("garage"))

        // Still suppressed just before the TTL...
        clock += RtspHealth.BREAKER_TTL_MS - 1
        assertFalse(RtspHealth.maybeAvailable("garage"))

        // ...and retryable once it elapses. Without this, `canRtsp` gates mounting the player, so
        // the success that would clear the verdict could never fire: a camera that was briefly
        // rebooting stayed demoted to the relayed tier for the rest of the process.
        clock += 2
        assertTrue(RtspHealth.maybeAvailable("garage"))
    }
}

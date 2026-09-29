package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTierTest {

    private fun tier(
        canRtsp: Boolean = false,
        useSub: Boolean = false,
        canGo2rtc: Boolean? = true,
        go2rtcFailed: Boolean = false,
        canWebRtc: Boolean = true,
        webRtcFailed: Boolean = false,
        hasHlsUrl: Boolean = false,
        hasMjpeg: Boolean = true,
    ) = liveTier(canRtsp, useSub, canGo2rtc, go2rtcFailed, canWebRtc, webRtcFailed, hasHlsUrl, hasMjpeg)

    @Test
    fun `the ladder steps down in order`() {
        assertEquals(LiveTier.DIRECT, tier(canRtsp = true))
        assertEquals(LiveTier.RELAY, tier())
        assertEquals(LiveTier.HA_WEBRTC, tier(go2rtcFailed = true))
        assertEquals(LiveTier.HLS, tier(go2rtcFailed = true, webRtcFailed = true, hasHlsUrl = true))
        assertEquals(LiveTier.MJPEG, tier(go2rtcFailed = true, webRtcFailed = true))
        assertEquals(LiveTier.SNAPSHOT, tier(go2rtcFailed = true, webRtcFailed = true, hasMjpeg = false))
    }

    @Test
    fun `low quality skips the direct tier and forces the relay even when it had failed before`() {
        assertEquals(LiveTier.RELAY, tier(canRtsp = true, useSub = true))
        assertEquals(LiveTier.RELAY, tier(canGo2rtc = false, useSub = true))
    }

    @Test
    fun `an undecided go2rtc list is connecting, not a slow stream`() {
        assertEquals(LiveTier.RESOLVING, tier(canGo2rtc = null))
        assertEquals(LiveTier.RESOLVING, tier(canGo2rtc = null, hasMjpeg = false))
        assertEquals(LiveTier.DIRECT, tier(canGo2rtc = null, canRtsp = true))
    }

    @Test
    fun `only real-time tiers are labelled plain Live`() {
        listOf(LiveTier.DIRECT, LiveTier.RELAY, LiveTier.HA_WEBRTC, LiveTier.HLS).forEach {
            assertEquals(LiveLabel("Live", realtime = true), liveLabel(it))
        }
        assertEquals("Live · reduced", liveLabel(LiveTier.MJPEG).text)
        assertFalse(liveLabel(LiveTier.MJPEG).realtime)
        assertEquals("Snapshots only", liveLabel(LiveTier.SNAPSHOT).text)
        assertTrue(listOf(LiveTier.RESOLVING, LiveTier.MJPEG, LiveTier.SNAPSHOT).none { liveLabel(it).realtime })
    }
}

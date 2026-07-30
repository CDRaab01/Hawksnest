package com.hawksnest.core.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Session circuit-breaker for the direct-RTSP live tier, keyed **per camera**.
 *
 * The go2rtc equivalent ([Go2rtcHealth]) is deliberately process-wide: its failure mode is one
 * shared piece of infrastructure being unreachable, so one camera's failure really does predict
 * every other camera's. Direct RTSP is the opposite — each camera is its own server. A camera that
 * is powered off, rebooting, or out of RTSP sessions says nothing about the others, so a global
 * breaker would let one dead camera silently downgrade the whole fleet to go2rtc for the session.
 *
 * Unknown cameras are assumed available: a wrong guess costs one fail-fast (~5 s worst case) and a
 * step-down, which is the same trade the go2rtc tier makes.
 */
object RtspHealth {
    private val healthy = ConcurrentHashMap<String, Boolean>()

    fun report(camera: String, ok: Boolean) {
        healthy[camera] = ok
    }

    /** Whether the RTSP tier is worth attempting for this camera (not known-broken this session). */
    fun maybeAvailable(camera: String): Boolean = healthy[camera] != false

    /** Test seam: forget this session's verdicts. */
    internal fun resetForTest() {
        healthy.clear()
    }
}

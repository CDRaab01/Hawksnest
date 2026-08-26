package com.hawksnest.core.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit-breaker for the direct-RTSP live tier, keyed **per camera**.
 *
 * The go2rtc equivalent ([Go2rtcHealth]) is deliberately process-wide: its failure mode is one
 * shared piece of infrastructure being unreachable, so one camera's failure really does predict
 * every other camera's. Direct RTSP is the opposite — each camera is its own server. A camera that
 * is powered off, rebooting, or out of RTSP sessions says nothing about the others, so a global
 * breaker would let one dead camera silently downgrade the whole fleet to go2rtc for the session.
 *
 * Unknown cameras are assumed available: a wrong guess costs one fail-fast (~5 s worst case) and a
 * step-down, which is the same trade the go2rtc tier makes.
 *
 * ## Verdicts EXPIRE, for the same reason [Go2rtcHealth]'s does
 *
 * These used to be permanent for the process. The shape is the trap: `CameraPlayer` reads
 * [maybeAvailable] into `canRtsp`, and that is what decides whether an `RtspPlayer` is mounted at
 * all — so once a camera's verdict was false, the `report(camera, true)` that would clear it could
 * never fire. A camera that was merely rebooting, or momentarily out of RTSP sessions, was demoted
 * to the relayed tier for the rest of the app's life with nothing logged and nothing shown.
 *
 * The blast radius was one camera rather than the fleet, which is the only reason this was less
 * severe than the go2rtc latch — not a difference in correctness. Both are fixed the same way:
 * record WHEN the failure happened and stop counting after [BREAKER_TTL_MS], so one open retries
 * and re-establishes the truth. A success still clears immediately.
 */
object RtspHealth {
    /** camera -> when it last failed. Absent = never failed (or cleared by a success). */
    private val failedAtMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var nowMs: () -> Long = System::currentTimeMillis

    fun report(camera: String, ok: Boolean) {
        if (ok) failedAtMs.remove(camera) else failedAtMs[camera] = nowMs()
    }

    /** Whether the RTSP tier is worth attempting for this camera (not known-broken right now). */
    fun maybeAvailable(camera: String): Boolean {
        val failed = failedAtMs[camera] ?: return true
        return nowMs() - failed >= BREAKER_TTL_MS
    }

    /** Test seam: forget the verdicts and restore the real clock. */
    internal fun resetForTest() {
        failedAtMs.clear()
        nowMs = System::currentTimeMillis
    }

    /** Test seam: drive [maybeAvailable]'s expiry without sleeping. */
    internal fun setClockForTest(clock: () -> Long) {
        nowMs = clock
    }

    /** Matches [Go2rtcHealth.BREAKER_TTL_MS] — same trade-off, same number, deliberately. */
    internal const val BREAKER_TTL_MS = 60_000L
}

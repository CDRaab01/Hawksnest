package com.hawksnest.core.logic

/**
 * Which rung of the live-transport ladder the camera player is on. One function decides it, and
 * both the ladder and the "Live" label read it, so the label can never claim more than the tier
 * that is actually drawing the picture.
 */
enum class LiveTier {
    /** RTSP straight to the camera. */
    DIRECT,

    /** WebRTC through the dedicated go2rtc. */
    RELAY,

    /** WebRTC through Home Assistant. */
    HA_WEBRTC,

    /** Segmented HLS (or the demo clip). */
    HLS,

    /** The tiers above are still being decided; the MJPEG proxy (or a snapshot) fills the wait. */
    RESOLVING,

    /** HA's MJPEG proxy: live, but low frame rate and quality. */
    MJPEG,

    /** Snapshots on a timer. Not live at all. */
    SNAPSHOT,
}

/**
 * The live ladder, in order. [canGo2rtc] null means go2rtc's stream list is still in flight, which
 * holds the HA-WebRTC tier so its negotiation isn't started only to be torn down.
 */
fun liveTier(
    canRtsp: Boolean,
    useSub: Boolean,
    canGo2rtc: Boolean?,
    go2rtcFailed: Boolean,
    canWebRtc: Boolean,
    webRtcFailed: Boolean,
    hasHlsUrl: Boolean,
    hasMjpeg: Boolean,
): LiveTier = when {
    // Low quality bypasses RTSP-direct: that tier plays the main stream.
    canRtsp && !useSub -> LiveTier.DIRECT
    (canGo2rtc == true || useSub) && !go2rtcFailed -> LiveTier.RELAY
    canGo2rtc != null && canWebRtc && !webRtcFailed -> LiveTier.HA_WEBRTC
    hasHlsUrl -> LiveTier.HLS
    canGo2rtc == null -> LiveTier.RESOLVING
    hasMjpeg -> LiveTier.MJPEG
    else -> LiveTier.SNAPSHOT
}

/** The player's status label for a tier, and whether it is real-time video (the green dot). */
data class LiveLabel(val text: String, val realtime: Boolean)

fun liveLabel(tier: LiveTier): LiveLabel = when (tier) {
    LiveTier.DIRECT, LiveTier.RELAY, LiveTier.HA_WEBRTC, LiveTier.HLS -> LiveLabel("Live", realtime = true)
    LiveTier.RESOLVING -> LiveLabel("Connecting", realtime = false)
    // It is the camera right now, but a few frames a second at detect resolution. The audit found
    // it labelled plain "Live", indistinguishable from the real thing.
    LiveTier.MJPEG -> LiveLabel("Live · reduced", realtime = false)
    LiveTier.SNAPSHOT -> LiveLabel("Snapshots only", realtime = false)
}

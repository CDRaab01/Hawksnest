package com.hawksnest.core.logic

/**
 * Picture-in-picture decisions for the camera player. Android-free so the aspect math and the
 * enter gate are JVM-testable; MainActivity wraps the results in platform types (Rational,
 * PictureInPictureParams).
 *
 * No web twin: system PiP is a platform feature — the web player minimizes by staying mounted
 * across route changes instead.
 */

/**
 * Whether leaving the app (home button / gesture) should minimize into PiP right now: a camera
 * is open and playing LIVE. Recorded playback deliberately backgrounds normally — a recording
 * can be resumed any time, and a silent floating window of old footage reads as a frozen live
 * feed.
 */
fun shouldEnterPip(sessionOpen: Boolean, isLive: Boolean): Boolean = sessionOpen && isLive

/**
 * PiP window aspect ratio as a width:height pair, from the source video's dimensions.
 * The platform rejects ratios outside 1:2.39..2.39:1 (IllegalArgumentException), so extreme
 * sources clamp to the nearest allowed edge; unknown/degenerate input falls back to 16:9 —
 * every camera in the house is 16:9, so the fallback is exact until the first frame reports.
 */
fun pipAspect(width: Int?, height: Int?): Pair<Int, Int> {
    if (width == null || height == null || width <= 0 || height <= 0) return 16 to 9
    val ratio = width.toDouble() / height
    return when {
        ratio > 2.39 -> 239 to 100
        ratio < 100.0 / 239.0 -> 100 to 239
        else -> width to height
    }
}

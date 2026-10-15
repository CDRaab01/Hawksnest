package com.hawksnest.core.logic

/**
 * What to show in the live frame while a live tier is still connecting — instead of black.
 *
 * Measured on the owner's phone (2026-10-14): the Reolink app puts a still of the camera on screen
 * in ~0.4 s and is live by ~0.5 s; Hawksnest showed a black rectangle for ~2.4 s (RTSP-direct waits
 * ~2 s for the camera to send anything) and, when a tier failed over, for 10 s or more. The camera
 * picture we already have makes that wait look like what it is — a camera coming up — rather than
 * a dead one. It is always drawn under the "Connecting…" scrim, so it is never passed off as live.
 */
enum class LivePosterSource {
    /** The frame grabbed the last time this camera played live in this process. */
    FRAME,

    /** The camera's snapshot — for a Frigate camera, Frigate's latest frame (seconds old). */
    SNAPSHOT,

    /** Nothing honest to show: the scrim alone, as before. */
    NONE,
}

/**
 * A grabbed frame this young beats fetching the snapshot: it is on the device already, so it paints
 * on the first frame of the open, and two minutes is no staler than a slow snapshot round trip.
 */
const val POSTER_FRAME_FRESH_MS = 120_000L

/**
 * Pick the still for the connecting wait.
 *
 * [sleeps] is a camera whose snapshot cannot be trusted while it is parked (a battery Reolink
 * behind the Home Hub serves Frigate's grey `camera-error.jpg` at HTTP 200 — the same reason Home's
 * tiles call it "Asleep"): only a grabbed frame is ever shown for it, however old.
 */
fun livePosterSource(
    frameCapturedAtMs: Long?,
    nowMs: Long,
    sleeps: Boolean,
    hasSnapshot: Boolean,
): LivePosterSource = when {
    frameCapturedAtMs != null && (sleeps || nowMs - frameCapturedAtMs <= POSTER_FRAME_FRESH_MS) ->
        LivePosterSource.FRAME
    !sleeps && hasSnapshot -> LivePosterSource.SNAPSHOT
    // An old frame still beats an empty box when there is no snapshot to fetch.
    frameCapturedAtMs != null -> LivePosterSource.FRAME
    else -> LivePosterSource.NONE
}

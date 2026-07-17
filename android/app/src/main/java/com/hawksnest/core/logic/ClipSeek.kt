package com.hawksnest.core.logic

/**
 * Timeline-time → clip mapping for live scrub-to-preview. Ring event clips arrive with
 * `endMs = null` (ring-mqtt's selector doesn't carry a duration) — the real span is only known once
 * the media loads, so containment takes the loaded clip's media duration as an input and assumes a
 * short span until then. Ported 1:1 from `src/lib/clipSeek.ts` (with its test suite).
 */

/** Span assumed for a clip whose real duration isn't known yet (matches the timeline's minimum
 *  chip width assumption for open-ended events). */
const val ASSUMED_CLIP_SPAN_MS = 30_000L

/**
 * The effective end of [e] on the timeline: its real `endMs` when known, else the loaded media
 * duration when [e] is the clip currently loaded in the player, else a conservative
 * [ASSUMED_CLIP_SPAN_MS].
 */
fun clipSpanEndMs(e: CameraEvent, loadedClipId: String?, loadedDurationMs: Long?): Long {
    e.endMs?.let { return it }
    if (loadedClipId == e.id && loadedDurationMs != null && loadedDurationMs > 0) {
        return e.startMs + loadedDurationMs
    }
    return e.startMs + ASSUMED_CLIP_SPAN_MS
}

/**
 * The clip whose `[startMs, spanEnd]` contains [t], or null when [t] falls in a gap. On overlap the
 * latest-starting clip wins (deterministic, so the player's clip-keyed effects can't thrash between
 * two ids at a boundary).
 */
fun clipContaining(
    events: List<CameraEvent>,
    t: Long,
    loadedClipId: String?,
    loadedDurationMs: Long?,
): CameraEvent? {
    var best: CameraEvent? = null
    for (e in events) {
        if (t < e.startMs || t > clipSpanEndMs(e, loadedClipId, loadedDurationMs)) continue
        if (best == null || e.startMs >= best.startMs) best = e
    }
    return best
}

/** Offset of [t] within clip [e], clamped ≥ 0, in ms (ExoPlayer seeks in ms). */
fun offsetInClipMs(e: CameraEvent, t: Long): Long = (t - e.startMs).coerceAtLeast(0L)

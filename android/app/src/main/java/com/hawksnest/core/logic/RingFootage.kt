package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The `ring-timeline` service's **24/7 continuous track** (`/footage`) — the thing `/timeline`
 * structurally cannot show. Ported 1:1 from `src/lib/ringFootage.ts` (with its test suite).
 *
 * `/timeline` is Ring's `video_search`, which only ever returns discrete *events*. That is why a
 * quiet 3–5 AM window comes back empty even on the seven cameras that record continuously: nothing
 * triggered, so there is no event, even though the footage exists. `/footage` reads Ring's Event
 * Video Manager timeline instead and returns the stitched continuous spans.
 *
 * Two consequences shape everything here:
 *  - Ring stitches server-side: a request for a wide window comes back as ONE segment covering all
 *    of it, not a pile of chunks. So the normal case is a single URL the player seeks around in —
 *    the same "one VOD for the whole window" shape the Frigate path already uses, which is why
 *    scrubbing across it doesn't re-init the player.
 *  - Not every camera has it. The battery cameras and the doorbell record events only, and answer
 *    with an empty list — an honest "no continuous track", not a failure. [RingFootage.continuous]
 *    says which.
 */

/** One stitched span of continuous recording (times are epoch ms). */
data class FootageSegment(
    val startMs: Long,
    val endMs: Long,
    val url: String?,
    /** When the pre-signed URL dies (~15 min out) — after this the footage must be refetched. */
    val urlExpiresAtMs: Long?,
    /** Ring can mark a span end-to-end encrypted; playing it needs a key no server here holds. */
    val encrypted: Boolean,
    val chunked: Boolean,
    val dingId: String?,
)

data class RingFootage(
    val segments: List<FootageSegment>,
    /** False for the battery cameras and the doorbell — they record events only. */
    val continuous: Boolean,
    /** Earliest signed-URL expiry in the set; the player refetches before this. */
    val expiresAtMs: Long?,
    /** Ring capped the result: the window is not fully covered by these segments. */
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = RingFootage(emptyList(), continuous = false, expiresAtMs = null, truncated = false)
    }
}

/** A drawable run of the continuous lane — neighbouring segments coalesced (see [footageSpans]). */
data class FootageSpan(
    val startMs: Long,
    val endMs: Long,
    /** False for encrypted/URL-less spans: footage exists but this player cannot show it. */
    val playable: Boolean,
)

/** A segment is playable when it has a URL and isn't end-to-end encrypted. */
fun FootageSegment.isPlayable(): Boolean = url != null && !encrypted

private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.boolOrFalse(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

/**
 * Parse `/footage`. Segments with unusable times are dropped rather than failing the whole
 * response — one malformed span must not cost the camera its continuous track.
 *
 * Encrypted and URL-less segments are KEPT. They are real coverage, and the lane showing them
 * greyed is the honest answer; hiding them would draw a gap where footage actually exists.
 */
fun parseRingFootage(body: JsonObject): RingFootage {
    val raw = body["segments"] as? JsonArray ?: JsonArray(emptyList())
    val segments = raw.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val startMs = obj.longOrNull("startMs") ?: return@mapNotNull null
        val endMs = obj.longOrNull("endMs") ?: return@mapNotNull null
        // A zero/negative span can't be seeked into and would draw as an invisible sliver.
        if (endMs <= startMs) return@mapNotNull null
        FootageSegment(
            startMs = startMs,
            endMs = endMs,
            url = obj.strOrNull("url"),
            urlExpiresAtMs = obj.longOrNull("urlExpiresAtMs"),
            encrypted = obj.boolOrFalse("encrypted"),
            chunked = obj.boolOrFalse("chunked"),
            dingId = obj.strOrNull("dingId"),
        )
    }.sortedBy { it.startMs }

    return RingFootage(
        segments = segments,
        // Trust our own parse over the server's flag: if nothing survived, there is no track to show.
        continuous = segments.isNotEmpty(),
        // Refresh against the FIRST URL to die, not the last.
        expiresAtMs = segments.mapNotNull { it.urlExpiresAtMs }.minOrNull(),
        truncated = body.boolOrFalse("truncated"),
    )
}

/**
 * The segment covering [t], or null. The interval is half-open (`start <= t < end`) so two
 * back-to-back segments never both claim the boundary instant — a closed interval made the player's
 * segment-keyed effects thrash between two ids while scrubbing across a seam.
 * On genuine overlap the latest-starting segment wins, matching [clipContaining].
 */
fun footageSegmentAt(segments: List<FootageSegment>, t: Long): FootageSegment? {
    var best: FootageSegment? = null
    for (seg in segments) {
        if (t < seg.startMs || t >= seg.endMs) continue
        val cur = best
        if (cur == null || seg.startMs >= cur.startMs) best = seg
    }
    return best
}

/** Offset of [t] within [seg], clamped into the span, in milliseconds (ExoPlayer seeks in ms). */
fun offsetInSegmentMs(seg: FootageSegment, t: Long): Long {
    val span = (seg.endMs - seg.startMs).coerceAtLeast(0L)
    return (t - seg.startMs).coerceIn(0L, span)
}

/**
 * Segments coalesced into drawable runs. Ring normally answers with one stitched segment, but a
 * window that spans a recording restart comes back as several abutting ones; drawn individually
 * they show hairline seams that read as gaps in coverage — which is exactly the thing this lane
 * exists to disprove. Only same-playability neighbours merge, so a greyed encrypted run stays
 * visually distinct from the playable footage either side of it.
 */
fun footageSpans(segments: List<FootageSegment>, toleranceMs: Long = 1000L): List<FootageSpan> {
    val spans = mutableListOf<FootageSpan>()
    for (seg in segments.sortedBy { it.startMs }) {
        val playable = seg.isPlayable()
        val last = spans.lastOrNull()
        if (last != null && last.playable == playable && seg.startMs - last.endMs <= toleranceMs) {
            spans[spans.lastIndex] = last.copy(endMs = maxOf(last.endMs, seg.endMs))
            continue
        }
        spans += FootageSpan(seg.startMs, seg.endMs, playable)
    }
    return spans
}

/**
 * What the player should show for a scrubbed moment. Pure so the two platforms cannot drift on the
 * one decision users actually notice — which of two possible sources plays.
 *
 * **Continuous footage wins over an event clip when both cover the moment.** Two reasons, both
 * borne out by the existing code: the whole window is one media source, so scrubbing across it
 * seeks instead of tearing down and re-initialising the player per clip (the documented cause of
 * the old scrub stutter and the backwards-seek crash); and the event blocks stay drawn on top as
 * markers, so nothing is lost by not *playing* them — tapping one still seeks to it, now inside a
 * continuous stream. Event clips remain the source on the cameras with no 24/7 track at all.
 */
sealed interface RecordedSource {
    data class Footage(val url: String, val seekToMs: Long, val segment: FootageSegment) : RecordedSource
    data class Clip(val url: String, val seekToMs: Long, val event: CameraEvent) : RecordedSource
    /** Footage exists here but this player can't decode it — say so, don't show an empty frame. */
    data object Encrypted : RecordedSource
    data object None : RecordedSource
}

fun chooseRecordedSource(
    headMs: Long,
    segments: List<FootageSegment>,
    events: List<CameraEvent>,
    /** Event id → playable URL (the timeline's `urls` map). */
    urls: Map<String, String>,
    loadedClipId: String?,
    loadedDurationMs: Long?,
): RecordedSource {
    val seg = footageSegmentAt(segments, headMs)
    if (seg != null && seg.isPlayable()) {
        return RecordedSource.Footage(seg.url!!, offsetInSegmentMs(seg, headMs), seg)
    }

    val event = clipContaining(events, headMs, loadedClipId, loadedDurationMs)
    val url = event?.let { urls[it.id] }
    if (event != null && url != null) {
        return RecordedSource.Clip(url, offsetInClipMs(event, headMs), event)
    }

    // Only now does an unplayable segment matter: with no clip to fall back on, "encrypted" is the
    // true reason there's no picture, and it is not the same message as "nothing was recorded".
    return if (seg != null) RecordedSource.Encrypted else RecordedSource.None
}

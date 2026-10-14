package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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

/**
 * The drawable span covering [t], or null — the [FootageSpan] twin of [footageSegmentAt], same
 * half-open interval and same latest-start-wins rule.
 *
 * This is what turns the continuous lane from decoration into a fact the player can act on:
 * "is there footage under the playhead?" Frigate's VOD cannot answer that itself — it concatenates
 * whatever segments exist in a range back-to-back, so a range straddling a gap plays with wall-clock
 * and playlist time disagreeing, and a range that is all gap 404s. Rare on a 24/7 camera; the
 * normal state of an event-only one (a battery Reolink behind a Home Hub, recorded only while its
 * PIR holds it awake). See [vodRangeFor]. 1:1 with `src/lib/ringFootage.ts`.
 */
fun footageSpanAt(spans: List<FootageSpan>, t: Long): FootageSpan? {
    var best: FootageSpan? = null
    for (span in spans) {
        if (t < span.startMs || t >= span.endMs) continue
        val cur = best
        if (cur == null || span.startMs >= cur.startMs) best = span
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
 * Tolerance when coalescing Frigate spans for **drawing**.
 *
 * Frigate writes ~10 s cache segments with sub-second seams between them, but a camera reconnect
 * or a Frigate restart can drop a segment, leaving a one-segment hole that is real but not worth
 * drawing. 15 s bridges those; anything longer renders as an honest gap in the lane.
 *
 * Applied by the lane itself (see [coalesceSpans]) — NOT at the parse boundary. It used to be the
 * parse default, which quietly made it the tolerance for the VOD range math too. See
 * [FRIGATE_MEDIA_TOLERANCE_MS] for why that was wrong.
 */
const val FRIGATE_LANE_TOLERANCE_MS = 15_000L

/**
 * Tolerance when coalescing Frigate segments into spans that describe the **media**.
 *
 * This is the one `vodRangeFor` reads, and it has to be near zero, because that function's whole
 * correctness argument is "the range is contiguous footage, so playlist time == wall-clock offset
 * from the range start". Frigate's VOD does not pad gaps — it concatenates the segments that
 * exist — so every millisecond bridged here is a millisecond the playhead and the picture drift
 * apart, cumulatively, for the rest of the page.
 *
 * Measured on `nursery_high` 2026-10-01: a stuck record process left ~1-2 s holes between every
 * ~10 s segment. All of them fell under the 15 s lane tolerance, so the lane reported ONE span
 * covering the whole 2-hour page, `vodRangeFor` narrowed nothing, and a scrub to 7:13:14 PM
 * played 7:23:28 PM — 10 minutes of silent drift, and a clip exported from that playhead came
 * back "wrong" because it was cut at the time the user marked rather than the time they saw.
 *
 * 500 ms, not 0: Frigate's segment times are floats and abutting segments routinely differ by a
 * few milliseconds. This merges rounding, nothing else.
 */
const val FRIGATE_MEDIA_TOLERANCE_MS = 500L

/**
 * Merge neighbouring spans separated by no more than [toleranceMs].
 *
 * The lane's half of the split above: parsing keeps the media-accurate spans, and whoever is
 * *drawing* widens them to taste. Same merge rule as [footageSpans] (only same-playability
 * neighbours merge) so the two coalescers cannot disagree about what a run is.
 */
fun coalesceSpans(spans: List<FootageSpan>, toleranceMs: Long): List<FootageSpan> {
    val out = mutableListOf<FootageSpan>()
    for (span in spans.sortedBy { it.startMs }) {
        val last = out.lastOrNull()
        if (last != null && last.playable == span.playable && span.startMs - last.endMs <= toleranceMs) {
            out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, span.endMs))
            continue
        }
        out += span
    }
    return out
}

/**
 * Unwrap a `frigate/recordings/get` websocket result into drawable [FootageSpan]s — the Frigate
 * counterpart of [footageSpans], and the data behind the continuous lane for Frigate cameras.
 * 1:1 with the web `parseFrigateWsRecordings`.
 *
 * Same websocket-only contract as `frigate/events/get` (see `parseFrigateWsEvents`): there is no
 * REST route for this, and the result usually arrives as a JSON **string** the integration didn't
 * decode. The payload is one entry per ~10 s recording segment (measured: ~6.5k entries / 1 MB /
 * tens of ms for a 3-day window), so coalescing here — not in the composable — is what keeps the
 * timeline from drawing thousands of runs. The default tolerance is the MEDIA one: a healthy
 * camera's segments abut, so they still collapse to a handful of spans, while a camera with real
 * holes keeps them instead of handing `vodRangeFor` a lie. The lane widens them for drawing.
 *
 * Spans are always `playable = true`: unlike Ring, Frigate has no per-segment URL to expire and no
 * end-to-end encryption — if the segment is on disk, the VOD can serve it. Junk input yields [],
 * never a throw: the lane simply doesn't render, which is what the pre-8b timeline showed anyway.
 */
fun parseFrigateWsRecordings(
    result: JsonElement?,
    toleranceMs: Long = FRIGATE_MEDIA_TOLERANCE_MS,
): List<FootageSpan> {
    val element = when {
        result is JsonPrimitive && result.isString ->
            try {
                Json.parseToJsonElement(result.content)
            } catch (_: Exception) {
                return emptyList()
            }
        else -> result
    }
    val arr = element as? JsonArray ?: return emptyList()
    val segments = arr.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val start = (obj["start_time"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val end = (obj["end_time"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        Math.round(start * 1000) to Math.round(end * 1000)
    }.sortedBy { it.first }
    val spans = mutableListOf<FootageSpan>()
    for ((startMs, endMs) in segments) {
        val last = spans.lastOrNull()
        if (last != null && startMs - last.endMs <= toleranceMs) {
            spans[spans.lastIndex] = last.copy(endMs = maxOf(last.endMs, endMs))
            continue
        }
        spans += FootageSpan(startMs, endMs, playable = true)
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

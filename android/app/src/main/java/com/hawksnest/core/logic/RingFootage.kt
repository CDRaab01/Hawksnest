package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/**
 * One stretch of genuinely contiguous recording inside a [FootageSpan] — segments that abut within
 * [FRIGATE_RUN_TOLERANCE_MS]. Its own type rather than [TimeRange] to mirror the web, where
 * `vodWindow.ts` imports `ringFootage.ts` and not the other way round.
 */
data class FootageRun(val startMs: Long, val endMs: Long)

/** A drawable run of the continuous lane — neighbouring segments coalesced (see [footageSpans]). */
data class FootageSpan(
    val startMs: Long,
    val endMs: Long,
    /** False for encrypted/URL-less spans: footage exists but this player cannot show it. */
    val playable: Boolean,
    /**
     * The contiguous sub-runs this span was DRAWN over, when the source knows them. A Frigate span
     * bridges holes of up to 15 s so the lane stays legible, but Frigate's VOD does not bridge
     * anything — it concatenates the segments that exist — so a seek computed from the span start
     * lands late by every hole before it. [vodRangeFor] mounts the run under the playhead instead,
     * where playlist time really is wall-clock offset. Empty (Ring) means "treat the span itself as
     * the run" — today's behaviour.
     */
    val runs: List<FootageRun> = emptyList(),
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

/**
 * The contiguous run covering [t], or null when [t] falls in a hole the span bridged for drawing.
 * Same half-open interval and latest-start-wins rule as [footageSpanAt], so a seam between two
 * abutting runs belongs to exactly one of them. 1:1 with the web `footageRunAt`.
 */
fun footageRunAt(runs: List<FootageRun>, t: Long): FootageRun? {
    var best: FootageRun? = null
    for (run in runs) {
        if (t < run.startMs || t >= run.endMs) continue
        val cur = best
        if (cur == null || run.startMs >= cur.startMs) best = run
    }
    return best
}

/**
 * The key that re-runs the Frigate footage-lane fetch while a camera stays open.
 *
 * The lane is otherwise fetched once per camera-open, which is right for a 24/7 camera (nothing
 * new to learn) and wrong for a battery camera behind a Home Hub: everything it records DURING the
 * session — each PIR wake — would never appear until the player was closed and reopened. HA flips
 * the Frigate camera entity's state around every wake (`idle` → `streaming`/`recording` → `idle`),
 * the same signal the tile's "Asleep" reads, so the state string IS the refresh key: one
 * transition, one refetch. For everything else the key is constant, so nothing refetches.
 * Attribute-only churn (battery %, snapshot republish) never changes it. 1:1 with the web
 * `footageLaneRefreshKey`.
 */
fun footageLaneRefreshKey(isBattery: Boolean, cameraState: String?): String? =
    if (isBattery) cameraState else null

/**
 * [footageLaneRefreshKey] over a live entity map: one emission per state transition of [entityId],
 * none for attribute-only churn, and a single constant emission for a camera that does not sleep.
 * The player keys its lane fetch on this, so every emission after the first is exactly one refetch.
 */
fun footageLaneRefreshKeys(
    entities: Flow<Map<String, HassEntity>>,
    entityId: String,
    isBattery: Boolean,
): Flow<String?> =
    entities.map { footageLaneRefreshKey(isBattery, it[entityId]?.state) }.distinctUntilChanged()

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
 * Tolerance when coalescing Frigate recording segments into drawable spans.
 *
 * Frigate writes ~10 s cache segments with sub-second seams between them, but a camera reconnect
 * or a Frigate restart can drop a segment, leaving a one-segment hole that is real but not worth
 * drawing. 15 s bridges those; anything longer renders as an honest gap in the lane.
 */
const val FRIGATE_SPAN_TOLERANCE_MS = 15_000L

/**
 * Tolerance when grouping Frigate segments into the contiguous RUNS inside a span.
 *
 * Frigate's real segment abutment is sub-second (a ~10 s segment ends where the next begins, give
 * or take the muxer). Anything wider is a hole the VOD will not fill: it concatenates the segments
 * that exist, so a seek measured from before the hole shows a frame that many seconds late. A
 * battery camera behind a Home Hub produces exactly this — its watchdog restarts ffmpeg around a
 * wake, leaving islands of 60 s segments littered with 0.04–2 s scraps tens of seconds apart —
 * and with only the 15 s drawing tolerance a scrub near the island's end overshot and clamped.
 */
const val FRIGATE_RUN_TOLERANCE_MS = 1_000L

/**
 * Unwrap a `frigate/recordings/get` websocket result into drawable [FootageSpan]s — the Frigate
 * counterpart of [footageSpans], and the data behind the continuous lane for Frigate cameras.
 * 1:1 with the web `parseFrigateWsRecordings`.
 *
 * Same websocket-only contract as `frigate/events/get` (see `parseFrigateWsEvents`): there is no
 * REST route for this, and the result usually arrives as a JSON **string** the integration didn't
 * decode. The payload is one entry per ~10 s recording segment (measured: ~6.5k entries / 1 MB /
 * tens of ms for a 3-day window), so coalescing here — not in the composable — is what keeps the
 * timeline from drawing thousands of runs.
 *
 * Two tolerances, two jobs. Segments within [toleranceMs] (15 s) merge into one DRAWN span, so a
 * single dropped cache segment does not render as a gap. Within a span, segments within
 * [runToleranceMs] (1 s) extend the current contiguous RUN and anything wider starts a new one —
 * so [vodRangeFor] can mount exactly the footage under the playhead and seek by wall-clock delta
 * without the bridged holes pushing the picture late. On a 24/7 camera one run == the span.
 *
 * Spans are always `playable = true`: unlike Ring, Frigate has no per-segment URL to expire and no
 * end-to-end encryption — if the segment is on disk, the VOD can serve it. Junk input yields [],
 * never a throw: the lane simply doesn't render, which is what the pre-8b timeline showed anyway.
 */
fun parseFrigateWsRecordings(
    result: JsonElement?,
    toleranceMs: Long = FRIGATE_SPAN_TOLERANCE_MS,
    runToleranceMs: Long = FRIGATE_RUN_TOLERANCE_MS,
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
            // Inside the drawn span. The last run always ends where the span ends, so the gap to it
            // is the gap to the span; sub-second (or overlapping) extends the run, wider opens a new one.
            val run = last.runs.last()
            val runs = if (startMs - run.endMs <= runToleranceMs) {
                last.runs.dropLast(1) + run.copy(endMs = maxOf(run.endMs, endMs))
            } else {
                last.runs + FootageRun(startMs, endMs)
            }
            spans[spans.lastIndex] = last.copy(endMs = maxOf(last.endMs, endMs), runs = runs)
            continue
        }
        spans += FootageSpan(startMs, endMs, playable = true, runs = listOf(FootageRun(startMs, endMs)))
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

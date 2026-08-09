package com.hawksnest.core.logic

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure math for **clip export**: turning a scrub position into a start/end range that Frigate can
 * actually cut, and deciding whether that range is exportable at all.
 *
 * **1:1 port of `src/lib/clipExport.ts`** — keep them in step. The unit tests are written as a
 * matched pair (`ClipExportTest.kt` ⇄ `clipExport.test.ts`, same cases, same numbers) precisely so
 * a drift shows up as a failing assertion rather than as two platforms quietly disagreeing about
 * what the user selected.
 *
 * The backend is Frigate's `clip.mp4`, reached through the HA Frigate integration's
 * `RecordingProxyView` (`/api/frigate/recording/<camera>/start/<s>/end/<e>`). It runs an ffmpeg
 * concat over the 60-second recording segments with integer-second `inpoint`/`outpoint`, which is
 * why [clipRangeSeconds] rounds *outwards* — see there.
 */

/**
 * Shortest exportable clip.
 *
 * The cut is `-c copy`, which cannot slice mid-GOP: ffmpeg starts at the first keyframe at or after
 * `inpoint`, and the in/out points are whole seconds. Under about five seconds a request can come
 * back with a single keyframe — or nothing — and that reads as a bug rather than as a short clip.
 */
const val MIN_CLIP_MS: Long = 5_000L

/**
 * Longest exportable clip (10 minutes, ~150–300 MB at these cameras' bitrates).
 *
 * Not an arbitrary tidiness limit. Frigate generates the file on demand and streams it, so the
 * whole run holds an ffmpeg process *and* an HA proxy connection open; the response has no
 * `Content-Length`, so neither platform can show real progress while it does. Ten minutes is long
 * enough for any incident worth keeping and short enough that "nothing is happening yet" never
 * lasts long enough to read as a hang.
 */
const val MAX_CLIP_MS: Long = 10 * 60_000L

/** Half-width of the selection seeded when clip mode opens (playhead ±15s). */
const val DEFAULT_HALF_MS: Long = 15_000L

/**
 * Touch tolerance for grabbing a selection handle, in pixels.
 *
 * Shared by both platforms so a handle that is grabbable on the web is grabbable on the phone. The
 * drawn handle is a hairline; this is the invisible target around it.
 */
const val HANDLE_HIT_SLOP_PX: Float = 20f

/** Fine adjust step. One second is ffmpeg's own granularity — anything smaller would be a lie. */
const val NUDGE_FINE_MS: Long = 1_000L

/**
 * Coarse adjust step. At the timeline's opening 1-hour zoom this is about 1.5 px, which is
 * precisely the range dragging cannot address — so the two instruments don't overlap.
 */
const val NUDGE_COARSE_MS: Long = 15_000L

/**
 * How far back from *now* the exportable range has to stop.
 *
 * Frigate writes 60-second recording segments, and the one currently being written is not in the
 * recordings table yet — so `clip.mp4` cannot see it and a selection running up to "now" comes back
 * short or 400s. Fails closed by one full segment.
 */
const val EXPORT_TAIL_LAG_MS: Long = 60_000L

/**
 * Slop when deciding whether a range is fully covered — edge rounding only, not gap bridging.
 *
 * It is tempting to reuse `RingFootage`'s 15 s span tolerance here so the validator and the lane
 * agree. That would be wrong twice over: the spans handed to [coverage] have *already* been
 * coalesced with that tolerance, so small holes are invisible by the time they arrive, and
 * re-applying 15 s would let a 30-second selection with half its footage missing report as
 * complete. Agreement with the lane comes from measuring the same spans, not from copying its
 * constant.
 */
private const val COVERAGE_TOLERANCE_MS: Long = 1_000L

/** An export range, in epoch ms. Always `startMs <= endMs`. */
data class ClipSelection(val startMs: Long, val endMs: Long)

/** Which end of the selection an interaction is moving. */
enum class ClipEdge { START, END }

/**
 * How much of the selection has recorded footage behind it.
 *
 * [ClipCoverage.UNKNOWN] is not a failure mode — it means the footage lane is empty, which happens
 * when the lookup failed or the source doesn't provide one. Coverage deliberately **fails open**
 * there: blocking an export because we couldn't ask is worse than letting Frigate answer for
 * itself. (Distinct from `Frigate.kt`, which fails *closed* on camera identification. Different
 * question: "is this a Frigate camera" must never guess yes; "is there footage here" may.)
 */
enum class ClipCoverage { FULL, PARTIAL, NONE, UNKNOWN }

/** Why a selection cannot be exported. Null from [selectionProblem] means it can. */
enum class ClipProblem { TOO_SHORT, TOO_LONG, NO_FOOTAGE }

/** Duration of a selection in ms. */
fun selectionDurationMs(sel: ClipSelection): Long = sel.endMs - sel.startMs

/**
 * The exportable range inside [window]: never past retention, never into the not-yet-recorded.
 *
 * Two separate ceilings collapse into one here. The timeline's window is padded past *now* so the
 * "Live" region can render, so it alone would let a selection be dragged into the future; and even
 * "now" is too far, because the segment currently being written is not yet queryable
 * (see [EXPORT_TAIL_LAG_MS]).
 *
 * Callers must pass a **live** [nowMs], not the pinned `nowAnchor` the timeline uses for its
 * layout: a player left open for hours would otherwise keep offering a start time that Frigate has
 * since rotated out of retention.
 */
fun exportBounds(window: TimeWindow, nowMs: Long): TimeWindow {
    val startMs = window.startMs
    val endMs = maxOf(startMs, minOf(window.endMs, nowMs - EXPORT_TAIL_LAG_MS))
    return TimeWindow(startMs, endMs)
}

/**
 * Normalise a selection: inside the bounds, ordered, and within the duration limits.
 *
 * [anchor] is the edge the user just set — it stays put and the *other* edge yields. Without it,
 * dragging the end handle would appear to move the start, because every duration fix would be
 * applied to the same end of the range.
 */
fun clampSelection(
    sel: ClipSelection,
    bounds: TimeWindow,
    anchor: ClipEdge = ClipEdge.START,
): ClipSelection {
    val lo = bounds.startMs
    val hi = maxOf(lo, bounds.endMs)
    val available = hi - lo
    // A range narrower than the minimum clip has exactly one answer: all of it.
    if (available <= MIN_CLIP_MS) return ClipSelection(lo, hi)

    // Resolve the duration FIRST, then place it. Clamping the two edges independently and fixing the
    // duration afterwards looks equivalent and is not: a selection overhanging the end of the range
    // gets its far edge pulled in, and the "fix" then reads that shortened span as the intent. A
    // 30-second clip marked at the live edge silently became 15 seconds that way.
    val requested = maxOf(0L, sel.endMs - sel.startMs)
    val duration = minOf(available, maxOf(MIN_CLIP_MS, minOf(MAX_CLIP_MS, requested)))

    // Then slide — never shrink — to fit, keeping the edge the user is holding where they put it.
    if (anchor == ClipEdge.START) {
        val startMs = minOf(maxOf(sel.startMs, lo), hi - duration)
        return ClipSelection(startMs, startMs + duration)
    }
    val endMs = maxOf(minOf(sel.endMs, hi), lo + duration)
    return ClipSelection(endMs - duration, endMs)
}

/** The selection clip mode opens with: [DEFAULT_HALF_MS] either side of the playhead. */
fun defaultSelection(playheadMs: Long, bounds: TimeWindow): ClipSelection =
    clampSelection(
        ClipSelection(playheadMs - DEFAULT_HALF_MS, playheadMs + DEFAULT_HALF_MS),
        bounds,
    )

/**
 * Move one edge to [ms] — the "Start here"/"End here" buttons and the drag handles.
 *
 * The moved edge is authoritative: if honouring it would break the duration limits, the opposite
 * edge moves out of the way. That is what makes "mark in, then mark out" work in either order.
 */
fun setEdge(sel: ClipSelection, edge: ClipEdge, ms: Long, bounds: TimeWindow): ClipSelection {
    val next =
        if (edge == ClipEdge.START) ClipSelection(ms, sel.endMs) else ClipSelection(sel.startMs, ms)
    return clampSelection(next, bounds, edge)
}

/** Shift one edge by [deltaMs] — the ±1s / ±15s fine-adjust buttons. */
fun nudge(sel: ClipSelection, edge: ClipEdge, deltaMs: Long, bounds: TimeWindow): ClipSelection {
    val from = if (edge == ClipEdge.START) sel.startMs else sel.endMs
    return setEdge(sel, edge, from + deltaMs, bounds)
}

/**
 * Which handle a press at [xPx] grabbed, or null for a press on neither.
 *
 * Takes **already-projected pixel positions** rather than a viewport, so the same function serves
 * the DOM and Compose without either platform's geometry types leaking in here. Ties go to
 * [ClipEdge.END]: when the two handles are on top of each other the selection is at its minimum,
 * and the edge the user can usefully move is the one that grows it.
 */
fun pickHandle(
    xPx: Float,
    startXPx: Float,
    endXPx: Float,
    slopPx: Float = HANDLE_HIT_SLOP_PX,
): ClipEdge? {
    val dStart = abs(xPx - startXPx)
    val dEnd = abs(xPx - endXPx)
    if (dStart > slopPx && dEnd > slopPx) return null
    if (dEnd > slopPx) return ClipEdge.START
    if (dStart > slopPx) return ClipEdge.END
    return if (dStart < dEnd) ClipEdge.START else ClipEdge.END
}

/**
 * How much of the selection is backed by recorded footage.
 *
 * The spans come from `frigate/recordings/get` (coalesced by `footageSpans`), which reads the same
 * `Recordings` table Frigate's own `clip.mp4` queries — so this answers, before we ask, the
 * question Frigate would otherwise answer with a 400. Only playable spans count: a span that
 * exists but cannot be decoded produces no video.
 */
fun coverage(sel: ClipSelection, spans: List<FootageSpan>): ClipCoverage {
    if (spans.isEmpty()) return ClipCoverage.UNKNOWN
    val duration = selectionDurationMs(sel)
    if (duration <= 0L) return ClipCoverage.NONE

    var covered = 0L
    for (span in spans) {
        if (!span.playable) continue
        val overlap = minOf(sel.endMs, span.endMs) - maxOf(sel.startMs, span.startMs)
        if (overlap > 0L) covered += overlap
    }

    if (covered <= 0L) return ClipCoverage.NONE
    return if (covered >= duration - COVERAGE_TOLERANCE_MS) ClipCoverage.FULL
    else ClipCoverage.PARTIAL
}

/**
 * Why this selection can't be exported, or null if it can.
 *
 * Partial coverage is deliberately **not** a problem — Frigate concatenates whatever exists, so the
 * export still succeeds and just comes back shorter. That is a warning for the UI to show (via
 * [coverage]), not a reason to refuse.
 */
fun selectionProblem(sel: ClipSelection, spans: List<FootageSpan>): ClipProblem? {
    val duration = selectionDurationMs(sel)
    if (duration < MIN_CLIP_MS) return ClipProblem.TOO_SHORT
    if (duration > MAX_CLIP_MS) return ClipProblem.TOO_LONG
    if (coverage(sel, spans) == ClipCoverage.NONE) return ClipProblem.NO_FOOTAGE
    return null
}

/** The integer-second range for the export URL. */
data class ClipRange(val startSec: Long, val endSec: Long)

/**
 * The integer-second range to put in the export URL.
 *
 * Rounded **outwards** (floor the start, ceil the end) on purpose: ffmpeg's `inpoint`/`outpoint`
 * are whole seconds, so rounding inwards would hand back a clip fractionally shorter than the one
 * the user marked — and the frame they were aiming at is exactly the one at the edge.
 */
fun clipRangeSeconds(sel: ClipSelection): ClipRange =
    ClipRange(
        startSec = floor(sel.startMs / 1000.0).toLong(),
        endSec = ceil(sel.endMs / 1000.0).toLong(),
    )

private val UNSAFE_NAME = Regex("[^a-zA-Z0-9_-]+")
private val EDGE_DASHES = Regex("^-+|-+$")

/**
 * Download filename: `kitchen-2026-08-09-10-42-15-50s.mp4`.
 *
 * Local time, not ISO/UTC — the timestamp has to match what the timeline showed when the user
 * marked the clip, or the file is unfindable later. (Same reasoning as the snapshot save, which
 * also stamps local time.)
 *
 * Formatted by arithmetic on [Calendar] fields rather than through a date-formatting API, because
 * the whole point of this file is that it produces byte-identical output to the JS twin, and
 * locale-sensitive formatters are exactly where those two would drift apart.
 */
fun clipFileName(cameraName: String, sel: ClipSelection): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = sel.startMs
    val stamp =
        String.format(
            Locale.US,
            "%04d-%02d-%02d-%02d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
    val seconds = maxOf(1L, Math.round(selectionDurationMs(sel) / 1000.0))
    val safeName =
        cameraName.replace(UNSAFE_NAME, "-").replace(EDGE_DASHES, "").ifEmpty { "camera" }
    return "$safeName-$stamp-${seconds}s.mp4"
}

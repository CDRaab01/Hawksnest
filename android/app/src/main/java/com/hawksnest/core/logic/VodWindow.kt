package com.hawksnest.core.logic

/**
 * Windowing for Frigate's continuous VOD. **1:1 port of `src/lib/vodWindow.ts`** — keep them in
 * step; the platforms must page identically or a scrub lands somewhere different on each.
 *
 * ## Why this exists
 *
 * A Frigate VOD manifest cannot span an arbitrary range. Frigate serves `/vod/` through
 * **nginx-vod-module**, whose durations array has a hard compile-time ceiling of ~1024 elements.
 * Past that nginx fails the request outright:
 *
 * ```
 * media_set_parse_durations: invalid number of elements in the durations array 1108
 * HTTP 503
 * ```
 *
 * Measured against the real backend 2026-07-29: 220 min / 940 segments returns 200, 230 min
 * returns 503. At the ~10-12s segments these cameras produce, the ceiling is ~3 hours, and it is
 * not configurable without rebuilding the nginx module.
 *
 * So the player CANNOT load one continuous VOD spanning the scrub range — which is what the
 * original design assumed with a 24h window, already 8x over the limit. The timeline spans the
 * full retention period (presentational, cheap) while the media is a bounded **page** that
 * follows the playhead.
 *
 * ## Why pages are grid-aligned
 *
 * Pages snap to a fixed epoch grid rather than centring on the playhead, so scrubbing *within* a
 * page yields the identical URL: the player does not re-prepare, the manifest stays cached, and
 * the `authSig` signature minted for that page stays valid. A playhead-centred window would mint
 * a new URL on every frame of a drag.
 */

/**
 * Span of one VOD page.
 *
 * Two hours. At ~10-12s segments that is ~600-720 entries, comfortably under the ~1024 ceiling
 * with headroom for shorter segments. Pushing toward the measured 3h limit would trade that
 * margin for fewer page turns — a bad trade, since exceeding the cap is a hard 503 rather than a
 * slow load.
 */
const val VOD_PAGE_MS: Long = 2 * 3_600_000L

/** Fallback retention when Frigate's config has not been read yet, in days. */
const val DEFAULT_RETENTION_DAYS: Double = 1.0

private const val DAY_MS: Long = 24 * 3_600_000L

/** A half-open time range, milliseconds since epoch. */
data class TimeRange(val startMs: Long, val endMs: Long)

/**
 * The scrubbable range: [retentionDays] back from [nowMs].
 *
 * This is the TIMELINE's span, not the media's — how far back recordings exist is how far back
 * the user can drag. Frigate reports it per camera as `record.continuous.days`, so pass that
 * through rather than hardcoding, or the UI drifts from the deployed retention.
 */
fun retentionRange(nowMs: Long, retentionDays: Double?): TimeRange {
    val days = if (retentionDays != null && retentionDays.isFinite() && retentionDays > 0) {
        retentionDays
    } else {
        DEFAULT_RETENTION_DAYS
    }
    return TimeRange(nowMs - (days * DAY_MS).toLong(), nowMs)
}

/**
 * The VOD page containing [headMs], clamped into [bounds].
 *
 * Grid-aligned (see above), so any playhead inside the same page yields the same range and so the
 * same URL. Clamping matters at both ends: the first page must not begin before recordings exist,
 * and the last must not end in the future — Frigate answers a future range with an empty or short
 * manifest, which reads as a dead player.
 */
fun vodPageFor(headMs: Long, bounds: TimeRange): TimeRange {
    val alignedStart = Math.floorDiv(headMs, VOD_PAGE_MS) * VOD_PAGE_MS
    val startMs = maxOf(alignedStart, bounds.startMs)
    val endMs = minOf(alignedStart + VOD_PAGE_MS, bounds.endMs)
    return TimeRange(startMs, maxOf(endMs, startMs))
}

/**
 * The VOD range to mount for [headMs]: the grid page, **bounded to the footage span under the
 * playhead** — or null when the lane says there is nothing there.
 *
 * [vodPageFor] alone assumes the page is solid footage. Frigate's VOD does not fill gaps: it
 * concatenates the recording segments that exist in the range back-to-back, so a page that is 5%
 * footage and 95% gap plays as a few minutes of video whose playlist time bears no relation to the
 * wall-clock position the user scrubbed to, and a page with no footage at all 404s. Continuous
 * cameras rarely hit this; an event-only camera lives in it — a battery Reolink behind a Home Hub
 * is recorded only while its PIR holds it awake, so its timeline is islands of footage in gap.
 *
 * Intersecting the page with the covering span fixes both: the range is contiguous footage, so
 * playlist time == wall-clock offset from the range start, and a scrub into a gap yields null so
 * the player shows "No saved recording for this moment" instead of mounting a URL that will fail.
 *
 * Two deliberate properties, shared with the web twin:
 * - **Unknown is not none.** With no spans at all (lane not resolved, or the fetch failed) this
 *   returns the plain page — exactly today's behaviour — the same convention `ClipExport.coverage`
 *   uses (`UNKNOWN` ≠ `NONE`). Only a lane that *has* answered can say "nothing here".
 * - **24/7 cameras are unchanged.** Their lane is one long span per uninterrupted run, so the
 *   intersection is the whole page and the URL is identical to before. Grid alignment survives:
 *   every playhead inside the same page *and* the same span yields the same range.
 *
 * 1:1 port of `src/lib/vodWindow.ts` `vodRangeFor` — keep in step.
 */
fun vodRangeFor(headMs: Long, bounds: TimeRange, spans: List<FootageSpan>): TimeRange? {
    val page = vodPageFor(headMs, bounds)
    if (spans.isEmpty()) return page
    val span = footageSpanAt(spans, headMs) ?: return null
    if (!span.playable) return null
    val startMs = maxOf(page.startMs, span.startMs)
    val endMs = minOf(page.endMs, span.endMs)
    return if (endMs > startMs) TimeRange(startMs, endMs) else null
}

/**
 * Whether the playhead has left the loaded page, so the media must be refetched.
 *
 * Compares page identity rather than the playhead, so it is false for every scrub inside the
 * current page — callers can use it directly as "do I need a new URL?".
 */
fun needsNewPage(loaded: TimeRange?, headMs: Long, bounds: TimeRange): Boolean {
    if (loaded == null) return true
    return vodPageFor(headMs, bounds) != loaded
}

/**
 * Playback offset (ms) for [headMs] within the page starting at [pageStartMs].
 *
 * The VOD's zero is the page start, not the timeline start. Getting this wrong seeks to a
 * plausible-looking but wrong moment, which is worse than an obvious failure.
 */
fun vodPositionMsInPage(headMs: Long, pageStartMs: Long): Long =
    maxOf(0L, headMs - pageStartMs)

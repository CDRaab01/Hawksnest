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

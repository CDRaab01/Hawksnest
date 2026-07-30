/**
 * Windowing for Frigate's continuous VOD.
 *
 * ## Why this exists
 *
 * A Frigate VOD manifest cannot span an arbitrary range. Frigate serves `/vod/` through
 * **nginx-vod-module**, whose durations array has a hard compile-time ceiling of ~1024 elements.
 * Ask for more and nginx fails the request outright:
 *
 * ```
 * media_set_parse_durations: invalid number of elements in the durations array 1108
 * HTTP 503
 * ```
 *
 * Measured against the real backend 2026-07-29: 220 min / 940 segments returns 200, 230 min
 * returns 503. At the ~10-12s segments these cameras produce, the practical ceiling is ~3 hours.
 * It is not configurable — raising it means rebuilding the nginx module.
 *
 * The player therefore CANNOT load "one continuous VOD spanning the window", which is what the
 * original design assumed with a 24h window — already 8x over the limit. Instead the timeline
 * spans the full retention period (presentational, cheap) while the media is a bounded **page**
 * that follows the playhead and is refetched when the playhead leaves it.
 *
 * ## Why pages are grid-aligned
 *
 * Pages snap to a fixed epoch grid rather than centring on the playhead. That means scrubbing
 * *within* a page produces the identical URL, so the player does not re-prepare, the manifest
 * stays cached, and — on Frigate — the existing `authSig` signature stays valid. A
 * playhead-centred window would mint a new URL on every pixel of drag.
 */

/**
 * Span of one VOD page.
 *
 * Two hours. At ~10-12s segments that is roughly 600-720 entries, comfortably under the ~1024
 * ceiling with headroom for shorter segments (a camera reconfigured to a smaller GOP produces
 * more of them). Raising this toward the measured 3h limit would trade that safety margin for
 * fewer page turns, which is a bad trade: exceeding the cap is a hard 503, not a slow load.
 */
export const VOD_PAGE_MS = 2 * 3600_000;

/** Fallback retention when Frigate's config has not been read yet, in days. */
export const DEFAULT_RETENTION_DAYS = 1;

/** A half-open time range, milliseconds since epoch. */
export interface TimeRange {
  startMs: number;
  endMs: number;
}

/**
 * The scrubbable range: `retentionDays` back from `nowMs`.
 *
 * This is the TIMELINE's span, not the media's — it is how far back recordings exist, so it is
 * how far back the user can drag. Frigate reports it per camera as `record.continuous.days`;
 * pass that through rather than hardcoding, so the UI tracks the deployed retention instead of
 * drifting from it.
 */
export function retentionRange(nowMs: number, retentionDays: number): TimeRange {
  const days = Number.isFinite(retentionDays) && retentionDays > 0
    ? retentionDays
    : DEFAULT_RETENTION_DAYS;
  return { startMs: nowMs - days * 24 * 3600_000, endMs: nowMs };
}

/**
 * The VOD page containing `headMs`, clamped into `bounds`.
 *
 * Grid-aligned (see the note above), so any playhead inside the same page yields the same range
 * and therefore the same URL. Clamping matters at both ends: the first page must not start before
 * recordings exist, and the last must not end in the future — Frigate answers a future range with
 * an empty or short manifest, which reads as a dead player.
 */
export function vodPageFor(headMs: number, bounds: TimeRange): TimeRange {
  const alignedStart = Math.floor(headMs / VOD_PAGE_MS) * VOD_PAGE_MS;
  const startMs = Math.max(alignedStart, bounds.startMs);
  const endMs = Math.min(alignedStart + VOD_PAGE_MS, bounds.endMs);
  return { startMs, endMs: Math.max(endMs, startMs) };
}

/**
 * Whether the playhead has left the loaded page and the media must be refetched.
 *
 * Compares page identity rather than the playhead, so this is false for every scrub inside the
 * current page — the caller can use it directly as "do I need a new URL?".
 */
export function needsNewPage(loaded: TimeRange | null, headMs: number, bounds: TimeRange): boolean {
  if (!loaded) return true;
  const next = vodPageFor(headMs, bounds);
  return next.startMs !== loaded.startMs || next.endMs !== loaded.endMs;
}

/**
 * Playback offset (seconds) for `headMs` within the page starting at `pageStartMs`.
 *
 * The VOD's zero is the page start, not the timeline start — getting this wrong seeks to a
 * plausible-looking but wrong moment, which is worse than an obvious failure.
 */
export function vodPositionSecondsInPage(headMs: number, pageStartMs: number): number {
  return Math.max(0, Math.floor((headMs - pageStartMs) / 1000));
}

import { coalesceSpans, FRIGATE_MEDIA_TOLERANCE_MS, type FootageSpan } from "./ringFootage";

/**
 * Incremental top-up for the Frigate footage lane (`frigate/recordings/get`).
 *
 * The lane query returns one row per ~10 s recording segment for the whole retention window —
 * measured 2026-10-30: ~725 KB for 3 days on one camera — and it was re-run on every camera open
 * (and on every wake of a battery camera). Recorded footage in the past does not change except by
 * being ADDED at the live edge or AGED OUT at the far end, so after one full fetch only the tail
 * since the last fetch needs asking for.
 *
 * Rules (1:1 with `core/logic/FootageCache.kt` — keep in step):
 *  - **Union, never replace.** Footage is only ever added inside the window, so the cached spans
 *    and the fresh tail are merged by coalescing at the MEDIA tolerance; a segment that landed late
 *    inside the overlap fills its hole instead of being lost.
 *  - **Overlap the seam** by {@link FOOTAGE_TOPUP_OVERLAP_MS}: the segment being written at the
 *    last fetch was not in Frigate's table yet.
 *  - **Full refetch** when there is no entry, the window starts before what was cached, or the
 *    entry is older than {@link FOOTAGE_CACHE_MAX_AGE_MS} (retention deletes from the far end and
 *    the union rule above cannot see deletions — a periodic full fetch can).
 *  - **Trim, don't fetch, the far end**: spans wholly before the window are dropped.
 *  - **An empty full result is not cached.** The fetch seam turns failures into `[]`; caching it
 *    would blank the lane until the entry aged out.
 */
export const FOOTAGE_TOPUP_OVERLAP_MS = 2 * 60_000;
export const FOOTAGE_CACHE_MAX_AGE_MS = 10 * 60_000;

export interface FootageCacheEntry {
  /** The window the spans were fetched for. */
  startMs: number;
  endMs: number;
  spans: FootageSpan[];
  /** When the last FULL fetch ran — top-ups do not reset it. */
  fullFetchAtMs: number;
}

export interface FootageFetchPlan {
  /** Query `[fromMs, endMs]`. */
  fromMs: number;
  /** True when this is a top-up merged into the cached spans, false for a full fetch. */
  topUp: boolean;
}

export function planFootageFetch(
  entry: FootageCacheEntry | undefined,
  startMs: number,
  endMs: number,
  nowMs: number,
): FootageFetchPlan {
  const full = { fromMs: startMs, topUp: false };
  if (!entry) return full;
  if (nowMs - entry.fullFetchAtMs > FOOTAGE_CACHE_MAX_AGE_MS) return full;
  if (startMs < entry.startMs || entry.endMs < startMs || endMs < entry.startMs) return full;
  return { fromMs: Math.max(startMs, entry.endMs - FOOTAGE_TOPUP_OVERLAP_MS), topUp: true };
}

/**
 * Apply a fetch result. Returns the spans to hand the player and the entry to store (undefined =
 * store nothing, keep whatever was there).
 */
export function applyFootageFetch(
  entry: FootageCacheEntry | undefined,
  plan: FootageFetchPlan,
  fresh: FootageSpan[],
  startMs: number,
  endMs: number,
  nowMs: number,
): { spans: FootageSpan[]; entry: FootageCacheEntry | undefined } {
  if (!plan.topUp || !entry) {
    if (fresh.length === 0) return { spans: fresh, entry: undefined };
    return { spans: fresh, entry: { startMs, endMs, spans: fresh, fullFetchAtMs: nowMs } };
  }
  const kept = entry.spans.filter((s) => s.endMs > startMs);
  const spans = coalesceSpans([...kept, ...fresh], FRIGATE_MEDIA_TOLERANCE_MS);
  return {
    spans,
    entry: { startMs, endMs: Math.max(endMs, entry.endMs), spans, fullFetchAtMs: entry.fullFetchAtMs },
  };
}

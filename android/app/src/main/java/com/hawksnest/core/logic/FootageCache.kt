package com.hawksnest.core.logic

/**
 * Incremental top-up for the Frigate footage lane (`frigate/recordings/get`).
 *
 * The lane query returns one row per ~10 s recording segment for the whole retention window —
 * measured 2026-10-30: ~725 KB for 3 days on one camera — and it was re-run on every camera open
 * (and on every wake of a battery camera). Recorded footage in the past only changes by being
 * ADDED at the live edge or AGED OUT at the far end, so after one full fetch only the tail since
 * the last fetch needs asking for.
 *
 * 1:1 port of `src/lib/footageCache.ts` — keep in step. The rules (union never replace, overlap
 * the seam, periodic full refetch for retention deletions, trim the far end, never cache an empty
 * full result) are documented there.
 */
const val FOOTAGE_TOPUP_OVERLAP_MS: Long = 2 * 60_000L
const val FOOTAGE_CACHE_MAX_AGE_MS: Long = 10 * 60_000L

data class FootageCacheEntry(
    /** The window the spans were fetched for. */
    val startMs: Long,
    val endMs: Long,
    val spans: List<FootageSpan>,
    /** When the last FULL fetch ran — top-ups do not reset it. */
    val fullFetchAtMs: Long,
)

data class FootageFetchPlan(
    /** Query `[fromMs, endMs]`. */
    val fromMs: Long,
    /** True when this is a top-up merged into the cached spans, false for a full fetch. */
    val topUp: Boolean,
)

fun planFootageFetch(entry: FootageCacheEntry?, startMs: Long, endMs: Long, nowMs: Long): FootageFetchPlan {
    val full = FootageFetchPlan(fromMs = startMs, topUp = false)
    if (entry == null) return full
    if (nowMs - entry.fullFetchAtMs > FOOTAGE_CACHE_MAX_AGE_MS) return full
    if (startMs < entry.startMs || entry.endMs < startMs || endMs < entry.startMs) return full
    return FootageFetchPlan(fromMs = maxOf(startMs, entry.endMs - FOOTAGE_TOPUP_OVERLAP_MS), topUp = true)
}

data class FootageFetchResult(val spans: List<FootageSpan>, val entry: FootageCacheEntry?)

/** Apply a fetch result: the spans to hand the player, and the entry to store (null = store nothing). */
fun applyFootageFetch(
    entry: FootageCacheEntry?,
    plan: FootageFetchPlan,
    fresh: List<FootageSpan>,
    startMs: Long,
    endMs: Long,
    nowMs: Long,
): FootageFetchResult {
    if (!plan.topUp || entry == null) {
        if (fresh.isEmpty()) return FootageFetchResult(fresh, null)
        return FootageFetchResult(fresh, FootageCacheEntry(startMs, endMs, fresh, nowMs))
    }
    val kept = entry.spans.filter { it.endMs > startMs }
    val spans = coalesceSpans(kept + fresh, FRIGATE_MEDIA_TOLERANCE_MS)
    return FootageFetchResult(
        spans,
        FootageCacheEntry(startMs, maxOf(endMs, entry.endMs), spans, entry.fullFetchAtMs),
    )
}

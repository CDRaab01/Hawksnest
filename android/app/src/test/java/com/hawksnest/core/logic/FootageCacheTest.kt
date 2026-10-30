package com.hawksnest.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `src/lib/__tests__/footageCache.test.ts` — the two platforms must top up identically. */
class FootageCacheTest {
    private val min = 60_000L
    private fun span(s: Long, e: Long) = FootageSpan(s, e, true)
    private fun entry(startMs: Long = 0, fullFetchAtMs: Long = 0) = FootageCacheEntry(
        startMs = startMs,
        endMs = 1000 * min,
        spans = listOf(span(0, 400 * min), span(500 * min, 1000 * min)),
        fullFetchAtMs = fullFetchAtMs,
    )

    @Test
    fun `full fetch with nothing cached`() {
        assertEquals(FootageFetchPlan(0, false), planFootageFetch(null, 0, 1000 * min, 0))
    }

    @Test
    fun `tops up from the cached end minus the overlap`() {
        assertEquals(
            FootageFetchPlan(1000 * min - FOOTAGE_TOPUP_OVERLAP_MS, true),
            planFootageFetch(entry(), 5 * min, 1005 * min, 5 * min),
        )
    }

    @Test
    fun `refetches fully once the entry is too old`() {
        assertFalse(planFootageFetch(entry(), 0, 1000 * min, FOOTAGE_CACHE_MAX_AGE_MS + 1).topUp)
    }

    @Test
    fun `refetches fully when the window reaches further back than the cache`() {
        assertFalse(planFootageFetch(entry(startMs = 100 * min), 0, 1000 * min, 0).topUp)
    }

    @Test
    fun `refetches fully when the window starts after everything cached`() {
        assertFalse(planFootageFetch(entry(), 2000 * min, 3000 * min, 0).topUp)
    }

    @Test
    fun `unions the tail into the cache and joins the seam`() {
        val e = entry()
        val plan = planFootageFetch(e, 5 * min, 1005 * min, 5 * min)
        val out = applyFootageFetch(e, plan, listOf(span(998 * min, 1005 * min)), 5 * min, 1005 * min, 5 * min)
        assertEquals(listOf(span(0, 400 * min), span(500 * min, 1005 * min)), out.spans)
        assertEquals(0L, out.entry?.fullFetchAtMs)
        assertEquals(1005 * min, out.entry?.endMs)
    }

    @Test
    fun `keeps a real hole that the top-up did not fill`() {
        val e = entry()
        val plan = planFootageFetch(e, 0, 1010 * min, 1)
        val out = applyFootageFetch(e, plan, listOf(span(1003 * min, 1010 * min)), 0, 1010 * min, 1)
        assertEquals(
            listOf(span(0, 400 * min), span(500 * min, 1000 * min), span(1003 * min, 1010 * min)),
            out.spans,
        )
    }

    @Test
    fun `trims spans that fell out of the window`() {
        val e = entry()
        val plan = planFootageFetch(e, 450 * min, 1000 * min, 1)
        val out = applyFootageFetch(e, plan, emptyList(), 450 * min, 1000 * min, 1)
        assertEquals(listOf(span(500 * min, 1000 * min)), out.spans)
    }

    @Test
    fun `does not cache an empty full fetch`() {
        val plan = planFootageFetch(null, 0, 10, 0)
        assertNull(applyFootageFetch(null, plan, emptyList(), 0, 10, 0).entry)
    }

    @Test
    fun `an empty top-up keeps what was cached`() {
        val e = entry()
        val plan = planFootageFetch(e, 0, 1000 * min, 1)
        assertEquals(e.spans, applyFootageFetch(e, plan, emptyList(), 0, 1000 * min, 1).spans)
    }
}

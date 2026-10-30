import { describe, expect, it } from "vitest";
import {
  applyFootageFetch,
  FOOTAGE_CACHE_MAX_AGE_MS,
  FOOTAGE_TOPUP_OVERLAP_MS,
  planFootageFetch,
  type FootageCacheEntry,
} from "../footageCache";

/** Mirrors `FootageCacheTest.kt` — the two platforms must top up identically. */
const min = 60_000;
const span = (startMs: number, endMs: number) => ({ startMs, endMs, playable: true });
const entry = (over: Partial<FootageCacheEntry> = {}): FootageCacheEntry => ({
  startMs: 0,
  endMs: 1000 * min,
  spans: [span(0, 400 * min), span(500 * min, 1000 * min)],
  fullFetchAtMs: 0,
  ...over,
});

describe("planFootageFetch", () => {
  it("does a full fetch with nothing cached", () => {
    expect(planFootageFetch(undefined, 0, 1000 * min, 0)).toEqual({ fromMs: 0, topUp: false });
  });

  it("tops up from the cached end minus the overlap", () => {
    expect(planFootageFetch(entry(), 5 * min, 1005 * min, 5 * min)).toEqual({
      fromMs: 1000 * min - FOOTAGE_TOPUP_OVERLAP_MS,
      topUp: true,
    });
  });

  it("refetches fully once the entry is too old (retention deletions are invisible to a top-up)", () => {
    const now = FOOTAGE_CACHE_MAX_AGE_MS + 1;
    expect(planFootageFetch(entry(), 0, 1000 * min, now).topUp).toBe(false);
  });

  it("refetches fully when the window reaches further back than the cache", () => {
    expect(planFootageFetch(entry({ startMs: 100 * min }), 0, 1000 * min, 0).topUp).toBe(false);
  });

  it("refetches fully when the window starts after everything cached", () => {
    expect(planFootageFetch(entry(), 2000 * min, 3000 * min, 0).topUp).toBe(false);
  });
});

describe("applyFootageFetch", () => {
  it("unions the tail into the cache and joins the seam", () => {
    const e = entry();
    const plan = planFootageFetch(e, 5 * min, 1005 * min, 5 * min);
    const out = applyFootageFetch(e, plan, [span(998 * min, 1005 * min)], 5 * min, 1005 * min, 5 * min);
    expect(out.spans).toEqual([span(0, 400 * min), span(500 * min, 1005 * min)]);
    expect(out.entry?.fullFetchAtMs).toBe(0);
    expect(out.entry?.endMs).toBe(1005 * min);
  });

  it("keeps a real hole that the top-up did not fill", () => {
    const e = entry();
    const plan = planFootageFetch(e, 0, 1010 * min, 1);
    const out = applyFootageFetch(e, plan, [span(1003 * min, 1010 * min)], 0, 1010 * min, 1);
    expect(out.spans).toEqual([span(0, 400 * min), span(500 * min, 1000 * min), span(1003 * min, 1010 * min)]);
  });

  it("trims spans that fell out of the window", () => {
    const e = entry();
    const plan = planFootageFetch(e, 450 * min, 1000 * min, 1);
    const out = applyFootageFetch(e, plan, [], 450 * min, 1000 * min, 1);
    expect(out.spans).toEqual([span(500 * min, 1000 * min)]);
  });

  it("does not cache an empty full fetch (failures arrive as [])", () => {
    const plan = planFootageFetch(undefined, 0, 10, 0);
    expect(applyFootageFetch(undefined, plan, [], 0, 10, 0).entry).toBeUndefined();
  });

  it("an empty top-up keeps what was cached", () => {
    const e = entry();
    const plan = planFootageFetch(e, 0, 1000 * min, 1);
    expect(applyFootageFetch(e, plan, [], 0, 1000 * min, 1).spans).toEqual(e.spans);
  });
});

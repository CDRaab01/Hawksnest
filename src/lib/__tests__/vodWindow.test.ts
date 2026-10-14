import { describe, expect, it } from "vitest";
import {
  DEFAULT_RETENTION_DAYS,
  VOD_PAGE_MS,
  needsNewPage,
  retentionRange,
  vodPageFor,
  vodPositionSecondsInPage,
  vodRangeFor,
} from "../vodWindow";

const HOUR = 3600_000;
const DAY = 24 * HOUR;

describe("retentionRange", () => {
  it("spans retentionDays back from now", () => {
    const now = 1_800_000_000_000;
    expect(retentionRange(now, 3)).toEqual({ startMs: now - 3 * DAY, endMs: now });
  });

  it("falls back when Frigate's retention is missing or nonsensical", () => {
    const now = 1_800_000_000_000;
    for (const bad of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(retentionRange(now, bad).startMs).toBe(now - DEFAULT_RETENTION_DAYS * DAY);
    }
  });
});

describe("vodPageFor", () => {
  const bounds = { startMs: 0, endMs: 100 * DAY };

  it("returns a page no longer than the segment-ceiling budget", () => {
    const page = vodPageFor(50 * DAY + 37 * 60_000, bounds);
    expect(page.endMs - page.startMs).toBeLessThanOrEqual(VOD_PAGE_MS);
  });

  it("is grid-aligned: every playhead in a page yields the SAME range", () => {
    // This is the property that stops a drag re-requesting the manifest on every move.
    const base = 50 * DAY;
    const first = vodPageFor(base, bounds);
    for (const offset of [1, 60_000, VOD_PAGE_MS - 1]) {
      expect(vodPageFor(base + offset, bounds)).toEqual(first);
    }
  });

  it("moves to a new page once the playhead crosses the boundary", () => {
    const base = Math.floor((50 * DAY) / VOD_PAGE_MS) * VOD_PAGE_MS;
    expect(vodPageFor(base + VOD_PAGE_MS, bounds).startMs).toBe(base + VOD_PAGE_MS);
  });

  it("never starts before recordings exist", () => {
    const b = { startMs: 10 * DAY + 1234, endMs: 11 * DAY };
    expect(vodPageFor(b.startMs, b).startMs).toBeGreaterThanOrEqual(b.startMs);
  });

  it("never ends in the future", () => {
    const now = 10 * DAY + 12345;
    const b = { startMs: now - 3 * DAY, endMs: now };
    expect(vodPageFor(now, b).endMs).toBeLessThanOrEqual(now);
  });

  it("degenerate bounds produce a non-inverted range", () => {
    const b = { startMs: 5 * DAY, endMs: 5 * DAY };
    const page = vodPageFor(5 * DAY, b);
    expect(page.endMs).toBeGreaterThanOrEqual(page.startMs);
  });
});

describe("needsNewPage", () => {
  const bounds = { startMs: 0, endMs: 100 * DAY };

  it("is true with nothing loaded", () => {
    expect(needsNewPage(null, 5 * DAY, bounds)).toBe(true);
  });

  it("is false while scrubbing inside the loaded page", () => {
    const head = 50 * DAY;
    const loaded = vodPageFor(head, bounds);
    expect(needsNewPage(loaded, head + 60_000, bounds)).toBe(false);
  });

  it("is true once the playhead crosses into the next page", () => {
    const head = 50 * DAY;
    const loaded = vodPageFor(head, bounds);
    expect(needsNewPage(loaded, loaded.endMs + 1, bounds)).toBe(true);
  });
});

describe("vodPositionSecondsInPage", () => {
  it("offsets from the PAGE start, not the timeline start", () => {
    const pageStart = 50 * DAY;
    expect(vodPositionSecondsInPage(pageStart + 90_000, pageStart)).toBe(90);
  });

  it("never returns a negative seek", () => {
    const pageStart = 50 * DAY;
    expect(vodPositionSecondsInPage(pageStart - 5000, pageStart)).toBe(0);
  });
});

describe("vodRangeFor", () => {
  const bounds = { startMs: 0, endMs: 100 * DAY };
  // 37 minutes into a page boundary (50 days is a multiple of the 2h grid).
  const head = 50 * DAY + 37 * 60_000;
  const page = vodPageFor(head, bounds);

  it("is the plain grid page while the lane has not answered — unknown is not none", () => {
    expect(vodRangeFor(head, bounds, [])).toEqual(page);
  });

  it("is identical to the grid page on a 24/7 camera whose span covers it", () => {
    const solid = [{ startMs: 0, endMs: 100 * DAY, playable: true }];
    expect(vodRangeFor(head, bounds, solid)).toEqual(page);
  });

  it("clamps the page to the footage island under the playhead", () => {
    const island = { startMs: head - 2 * 60_000, endMs: head + 3 * 60_000, playable: true };
    expect(vodRangeFor(head, bounds, [island])).toEqual({
      startMs: island.startMs,
      endMs: island.endMs,
    });
  });

  it("is null in a known gap, so the player says 'no recording' instead of mounting a 404", () => {
    const elsewhere = [{ startMs: head + HOUR, endMs: head + 2 * HOUR, playable: true }];
    expect(vodRangeFor(head, bounds, elsewhere)).toBeNull();
  });

  it("treats an unplayable span as a gap", () => {
    const unplayable = [{ startMs: 0, endMs: 100 * DAY, playable: false }];
    expect(vodRangeFor(head, bounds, unplayable)).toBeNull();
  });

  it("keeps one range for every playhead inside the same island and page", () => {
    const island = [{ startMs: head - 10 * 60_000, endMs: head + 10 * 60_000, playable: true }];
    expect(vodRangeFor(head, bounds, island)).toEqual(vodRangeFor(head + 60_000, bounds, island));
  });
});

describe("regression: wall-clock and playlist time must not drift (nursery_high, 2026-10-01)", () => {
  // A stuck Frigate record process left ~2 s holes between every ~10 s segment on one camera.
  // Every hole was under the lane's 15 s coalescing tolerance, which was ALSO the parse default,
  // so the lane reported one unbroken span across the whole 2-hour page. `vodRangeFor` therefore
  // narrowed nothing, and `vodPositionSecondsInPage` — which assumes the page is solid footage —
  // seeked by wall-clock offset into a playlist that is only the surviving footage concatenated.
  // A scrub to 7:13:14 PM played 7:23:28 PM, and a clip exported from that playhead was cut at
  // the marked time rather than the time on screen, so it came back "the wrong time".
  const PAGE_START = 1790805600_000; // 18:00:00 local, a 2h grid boundary
  const bounds = { startMs: PAGE_START, endMs: PAGE_START + 4 * HOUR };
  const TARGET = 1790809994_000; // 19:13:14 local — what the user marked

  /** The real shape: 10 s of footage every 12 s of wall clock. */
  const gappy = Array.from({ length: 600 }, (_, i) => ({
    startMs: PAGE_START + i * 12_000,
    endMs: PAGE_START + i * 12_000 + 10_000,
    playable: true,
  }));

  it("narrows the page to the contiguous segment under the playhead", () => {
    const range = vodRangeFor(TARGET, bounds, gappy);
    expect(range).not.toBeNull();
    // Not the whole 2h page — just the one ~10 s run the playhead is actually inside.
    expect(range!.endMs - range!.startMs).toBeLessThanOrEqual(10_000);
    expect(range!.startMs).toBeLessThanOrEqual(TARGET);
    expect(range!.endMs).toBeGreaterThan(TARGET);
  });

  it("keeps the seek honest: offset into the range is offset into the media", () => {
    const range = vodRangeFor(TARGET, bounds, gappy)!;
    // The invariant vodRangeFor exists to guarantee: because the range is contiguous footage,
    // wall-clock offset from its start IS the playlist position. Under the old behaviour the
    // range was the full page and this offset was 4394 s into a playlist holding only ~3855 s of
    // footage before that moment — the 10-minute drift.
    const pos = vodPositionSecondsInPage(TARGET, range.startMs);
    expect(pos).toBeLessThanOrEqual(10);
    expect(pos).toBe(Math.floor((TARGET - range.startMs) / 1000));
  });

  it("would have drifted ~10 minutes with the page unnarrowed", () => {
    // Pin the old failure so nobody restores the single-tolerance shortcut by accident.
    const naive = vodPositionSecondsInPage(TARGET, PAGE_START);
    const footageBefore =
      gappy
        .filter((s) => s.startMs < TARGET)
        .reduce((a, s) => a + (Math.min(s.endMs, TARGET) - s.startMs), 0) / 1000;
    expect(naive).toBe(4394);
    expect(Math.round(naive - footageBefore)).toBeGreaterThan(600);
  });

  it("a scrub into one of the holes is an honest 'no recording', not a wrong frame", () => {
    const inHole = PAGE_START + 11_000; // between segment 0 (ends +10s) and segment 1 (+12s)
    expect(vodRangeFor(inHole, bounds, gappy)).toBeNull();
  });
});

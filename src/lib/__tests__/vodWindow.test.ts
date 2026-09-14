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

  // One drawn span (the 15 s drawing tolerance bridged an 8 s hole) made of two contiguous runs.
  // Frigate's VOD plays whatever exists back-to-back, so a range that straddled the hole would
  // show a frame 8 s late for every moment after it.
  const bridged = {
    startMs: head - 60_000,
    endMs: head + 60_000,
    playable: true,
    runs: [
      { startMs: head - 60_000, endMs: head - 10_000 },
      { startMs: head - 2_000, endMs: head + 60_000 },
    ],
  };

  it("scrub past a bridged hole mounts the second run", () => {
    // The range starts at the RUN, so playlist time == wall-clock offset from head - 2 s.
    expect(vodRangeFor(head, bounds, [bridged])).toEqual({ startMs: head - 2_000, endMs: head + 60_000 });
    // And inside the first run, the first run — never the span.
    expect(vodRangeFor(head - 30_000, bounds, [bridged])).toEqual({
      startMs: head - 60_000,
      endMs: head - 10_000,
    });
  });

  it("scrub into a bridged hole yields null", () => {
    // Drawn as footage, but nothing was recorded here and the VOD cannot show it.
    expect(vodRangeFor(head - 5_000, bounds, [bridged])).toBeNull();
    // Half-open like footageSpanAt: the first run's end is the hole's first instant.
    expect(vodRangeFor(head - 10_000, bounds, [bridged])).toBeNull();
    expect(vodRangeFor(head - 2_000, bounds, [bridged])).not.toBeNull();
  });

  it("a solid span yields the same range as before", () => {
    // A 24/7 camera's span is one run == the span, so the URL is byte-identical to the run-less
    // form — no regression on the cameras this never applied to.
    const solid = { startMs: 0, endMs: 100 * DAY, playable: true };
    const withRun = { ...solid, runs: [{ startMs: 0, endMs: 100 * DAY }] };
    expect(vodRangeFor(head, bounds, [withRun])).toEqual(vodRangeFor(head, bounds, [solid]));
    expect(vodRangeFor(head, bounds, [withRun])).toEqual(page);
    const island = { startMs: head - 2 * 60_000, endMs: head + 3 * 60_000, playable: true };
    const islandRun = { ...island, runs: [{ startMs: island.startMs, endMs: island.endMs }] };
    expect(vodRangeFor(head, bounds, [islandRun])).toEqual(vodRangeFor(head, bounds, [island]));
  });
});

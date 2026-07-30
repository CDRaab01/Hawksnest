import { describe, expect, it } from "vitest";
import {
  DEFAULT_RETENTION_DAYS,
  VOD_PAGE_MS,
  needsNewPage,
  retentionRange,
  vodPageFor,
  vodPositionSecondsInPage,
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

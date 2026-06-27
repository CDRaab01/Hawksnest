import { describe, it, expect } from "vitest";
import {
  HOUR_MS,
  MINUTE_MS,
  MIN_SPAN_MS,
  type TimeWindow,
  type Viewport,
  clampCenter,
  clampMsPerPx,
  pan,
  tickIntervalMs,
  ticks,
  timeToX,
  viewportForSpan,
  visibleRange,
  visibleSpanMs,
  xToTime,
  zoom,
} from "../timelineViewport";

const W = 1000; // track width px
// A 24h window ending at a round epoch.
const END = 1_700_000_000_000;
const WINDOW: TimeWindow = { startMs: END - 24 * HOUR_MS, endMs: END };

// A 1h-visible viewport centered 2h before the live edge.
const vp = (): Viewport => viewportForSpan(END - 2 * HOUR_MS, HOUR_MS, W, WINDOW);

describe("timelineViewport mapping", () => {
  it("is center-anchored: centerMs maps to the track middle and back", () => {
    const v = vp();
    expect(timeToX(v.centerMs, v, W)).toBeCloseTo(W / 2, 6);
    expect(xToTime(W / 2, v, W)).toBeCloseTo(v.centerMs, 6);
  });

  it("round-trips time ↔ x", () => {
    const v = vp();
    for (const x of [0, 250, 500, 750, 1000]) {
      expect(timeToX(xToTime(x, v, W), v, W)).toBeCloseTo(x, 6);
    }
  });

  it("visibleSpanMs reflects the zoom", () => {
    expect(visibleSpanMs(vp(), W)).toBeCloseTo(HOUR_MS, 3);
  });
});

describe("pan", () => {
  it("drag right goes back in time; drag left goes forward", () => {
    const v = vp();
    expect(pan(v, 100, W, WINDOW).centerMs).toBeLessThan(v.centerMs);
    expect(pan(v, -100, W, WINDOW).centerMs).toBeGreaterThan(v.centerMs);
  });

  it("cannot pan past the live edge (center stays ≤ end - halfSpan)", () => {
    const v = vp();
    const far = pan(v, -10_000, W, WINDOW); // huge drag toward 'now'
    const half = visibleSpanMs(far, W) / 2;
    expect(far.centerMs).toBeLessThanOrEqual(WINDOW.endMs - half + 1);
  });

  it("cannot pan past the window start", () => {
    const v = vp();
    const far = pan(v, 10_000, W, WINDOW);
    const half = visibleSpanMs(far, W) / 2;
    expect(far.centerMs).toBeGreaterThanOrEqual(WINDOW.startMs + half - 1);
  });
});

describe("zoom", () => {
  it("zooming in (factor > 1) shrinks the visible span about the center", () => {
    const v = vp();
    const z = zoom(v, 2, W, WINDOW);
    expect(z.centerMs).toBeCloseTo(v.centerMs, 6);
    expect(visibleSpanMs(z, W)).toBeLessThan(visibleSpanMs(v, W));
  });

  it("clamps zoom-in at MIN_SPAN and zoom-out at the window span", () => {
    const v = vp();
    const tightest = zoom(v, 1e6, W, WINDOW);
    expect(visibleSpanMs(tightest, W)).toBeCloseTo(MIN_SPAN_MS, 3);
    const widest = zoom(v, 1e-6, W, WINDOW);
    expect(visibleSpanMs(widest, W)).toBeCloseTo(24 * HOUR_MS, 3);
  });
});

describe("clamps with a degenerate window", () => {
  it("pins center to the midpoint when the window is narrower than the span", () => {
    const tiny: TimeWindow = { startMs: 0, endMs: 5 * MINUTE_MS };
    const c = clampCenter(0, W, (10 * MINUTE_MS) / W, tiny);
    expect(c).toBeCloseTo(2.5 * MINUTE_MS, 3);
  });

  it("never lets the visible span exceed a sub-10-min window", () => {
    const tiny: TimeWindow = { startMs: 0, endMs: 5 * MINUTE_MS };
    const mpp = clampMsPerPx(HOUR_MS / W, W, tiny);
    expect(mpp * W).toBeLessThanOrEqual(5 * MINUTE_MS + 1);
  });
});

describe("ticks", () => {
  it("picks a coarser interval as the span widens", () => {
    expect(tickIntervalMs(HOUR_MS)).toBeLessThan(tickIntervalMs(12 * HOUR_MS));
  });

  it("returns tick times within the visible range, on the interval grid", () => {
    const v = vp();
    const t = ticks(v, W);
    const { startMs, endMs } = visibleRange(v, W);
    const interval = tickIntervalMs(endMs - startMs);
    expect(t.length).toBeGreaterThan(0);
    for (const x of t) {
      expect(x).toBeGreaterThanOrEqual(startMs);
      expect(x).toBeLessThanOrEqual(endMs);
      expect(x % interval).toBe(0);
    }
  });
});

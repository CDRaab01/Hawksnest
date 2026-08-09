import { describe, expect, it } from "vitest";
import type { FootageSpan } from "../ringFootage";
import {
  DEFAULT_HALF_MS,
  EXPORT_TAIL_LAG_MS,
  HANDLE_HIT_SLOP_PX,
  MAX_CLIP_MS,
  MIN_CLIP_MS,
  clampSelection,
  clipFileName,
  clipRangeSeconds,
  coverage,
  defaultSelection,
  exportBounds,
  nudge,
  pickHandle,
  selectionDurationMs,
  selectionProblem,
  setEdge,
} from "../clipExport";

/**
 * The parity contract for clip export. `core/logic/ClipExportTest.kt` carries the *same cases with
 * the same numbers* — the two files are meant to be diffable. If you change an assertion here,
 * change it there, or the platforms have silently forked.
 */

const NOW = 1_800_000_000_000;
const DAY = 86_400_000;
/** A 3-day retention window padded past now, as the timeline actually supplies it. */
const WINDOW = { startMs: NOW - 3 * DAY, endMs: NOW + 1_800_000 };
const B = exportBounds(WINDOW, NOW);

const span = (startMs: number, endMs: number, playable = true): FootageSpan => ({
  startMs,
  endMs,
  playable,
});

describe("exportBounds", () => {
  it("stops one segment short of now, so the segment still being written is never requested", () => {
    expect(exportBounds(WINDOW, NOW).endMs).toBe(NOW - EXPORT_TAIL_LAG_MS);
  });

  it("keeps the retention floor and never inverts when the window is degenerate", () => {
    expect(exportBounds(WINDOW, NOW).startMs).toBe(WINDOW.startMs);
    const tiny = { startMs: NOW, endMs: NOW };
    expect(exportBounds(tiny, NOW).endMs).toBe(NOW);
  });

  it("honours a window that ends before the tail lag would", () => {
    const early = { startMs: NOW - DAY, endMs: NOW - 10 * 60_000 };
    expect(exportBounds(early, NOW).endMs).toBe(early.endMs);
  });
});

describe("defaultSelection", () => {
  it("opens centred on the playhead", () => {
    const head = NOW - DAY;
    expect(defaultSelection(head, B)).toEqual({
      startMs: head - DEFAULT_HALF_MS,
      endMs: head + DEFAULT_HALF_MS,
    });
  });

  it("slides inside the bounds rather than shrinking when the playhead is at an edge", () => {
    const sel = defaultSelection(B.endMs, B);
    expect(sel.endMs).toBe(B.endMs);
    expect(selectionDurationMs(sel)).toBe(2 * DEFAULT_HALF_MS);
  });
});

describe("clampSelection", () => {
  it("grows a too-short range to the minimum", () => {
    const sel = clampSelection({ startMs: NOW - DAY, endMs: NOW - DAY + 500 }, B);
    expect(selectionDurationMs(sel)).toBe(MIN_CLIP_MS);
  });

  it("trims a too-long range to the maximum", () => {
    const sel = clampSelection({ startMs: NOW - DAY, endMs: NOW - DAY + 3 * MAX_CLIP_MS }, B);
    expect(selectionDurationMs(sel)).toBe(MAX_CLIP_MS);
  });

  it("moves the edge the user is NOT holding", () => {
    const start = NOW - DAY;
    const over = { startMs: start, endMs: start + 3 * MAX_CLIP_MS };
    // Anchored on start: the end yields.
    expect(clampSelection(over, B, "start")).toEqual({ startMs: start, endMs: start + MAX_CLIP_MS });
    // Anchored on end: the start yields instead.
    expect(clampSelection(over, B, "end")).toEqual({
      startMs: over.endMs - MAX_CLIP_MS,
      endMs: over.endMs,
    });
  });

  it("never returns an inverted range", () => {
    const sel = clampSelection({ startMs: NOW - DAY, endMs: NOW - 2 * DAY }, B);
    expect(sel.endMs).toBeGreaterThanOrEqual(sel.startMs);
  });

  it("collapses to the whole range when the bounds are narrower than a minimum clip", () => {
    const narrow = { startMs: NOW, endMs: NOW + 1_000 };
    expect(clampSelection({ startMs: NOW - DAY, endMs: NOW + DAY }, narrow)).toEqual(narrow);
  });
});

describe("setEdge", () => {
  it("keeps the moved edge exactly where it was put", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };
    const target = NOW - DAY + 20_000;
    expect(setEdge(sel, "start", target, B).startMs).toBe(target);
    expect(setEdge(sel, "end", target, B).endMs).toBe(target);
  });

  it("pins rather than swaps when an edge is dragged past the other", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };
    const dragged = setEdge(sel, "start", sel.endMs + 60_000, B);
    expect(dragged.startMs).toBeLessThan(dragged.endMs);
    expect(selectionDurationMs(dragged)).toBe(MIN_CLIP_MS);
  });

  it("cannot place an edge past the export ceiling", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };
    expect(setEdge(sel, "end", NOW + DAY, B).endMs).toBe(B.endMs);
  });

  it("cannot place an edge before retention", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };
    expect(setEdge(sel, "start", B.startMs - DAY, B).startMs).toBe(B.startMs);
  });
});

describe("nudge", () => {
  it("shifts one edge by the delta and leaves the other alone", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };
    const out = nudge(sel, "end", 15_000, B);
    expect(out.endMs).toBe(sel.endMs + 15_000);
    expect(out.startMs).toBe(sel.startMs);
  });

  it("refuses to nudge below the minimum duration", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + MIN_CLIP_MS };
    expect(selectionDurationMs(nudge(sel, "end", -60_000, B))).toBe(MIN_CLIP_MS);
  });
});

describe("pickHandle", () => {
  it("returns null when the press is near neither handle", () => {
    expect(pickHandle(500, 100, 200)).toBeNull();
  });

  it("grabs whichever handle is within slop", () => {
    expect(pickHandle(100 + HANDLE_HIT_SLOP_PX - 1, 100, 400)).toBe("start");
    expect(pickHandle(400 - HANDLE_HIT_SLOP_PX + 1, 100, 400)).toBe("end");
  });

  it("prefers the nearer handle, and breaks an exact tie towards end", () => {
    expect(pickHandle(105, 100, 130)).toBe("start");
    expect(pickHandle(115, 100, 130)).toBe("end");
    expect(pickHandle(100, 100, 100)).toBe("end");
  });
});

describe("coverage", () => {
  const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };

  it("reports unknown — not none — when there is no lane data at all", () => {
    // The lane is absent while the fetch is in flight and on sources that don't provide one.
    // Reporting "none" there would permanently disable export on a transient failure.
    expect(coverage(sel, [])).toBe("unknown");
  });

  it("reports full when a span encloses the selection", () => {
    expect(coverage(sel, [span(sel.startMs - 60_000, sel.endMs + 60_000)])).toBe("full");
  });

  it("reports none when no span overlaps", () => {
    expect(coverage(sel, [span(NOW - 2 * DAY, NOW - 2 * DAY + 60_000)])).toBe("none");
  });

  it("reports partial when the selection straddles a gap", () => {
    const spans = [
      span(sel.startMs - 60_000, sel.startMs + 15_000),
      span(sel.endMs - 15_000, sel.endMs + 60_000),
    ];
    expect(coverage(sel, spans)).toBe("partial");
  });

  it("does not count footage that exists but cannot be decoded", () => {
    expect(coverage(sel, [span(sel.startMs, sel.endMs, false)])).toBe("none");
  });
});

describe("selectionProblem", () => {
  const covered = [span(NOW - 3 * DAY, NOW)];

  it("passes a well-formed, covered selection", () => {
    expect(selectionProblem({ startMs: NOW - DAY, endMs: NOW - DAY + 60_000 }, covered)).toBeNull();
  });

  it("rejects durations outside the limits", () => {
    expect(selectionProblem({ startMs: NOW - DAY, endMs: NOW - DAY + 1_000 }, covered)).toBe(
      "too-short",
    );
    expect(
      selectionProblem({ startMs: NOW - DAY, endMs: NOW - DAY + MAX_CLIP_MS + 1_000 }, covered),
    ).toBe("too-long");
  });

  it("rejects a range with no footage behind it", () => {
    expect(selectionProblem({ startMs: NOW - DAY, endMs: NOW - DAY + 60_000 }, [])).toBeNull();
    expect(
      selectionProblem({ startMs: NOW - DAY, endMs: NOW - DAY + 60_000 }, [
        span(NOW - 2 * DAY, NOW - 2 * DAY + 1_000),
      ]),
    ).toBe("no-footage");
  });

  it("allows a partially covered range — Frigate returns what exists", () => {
    const sel = { startMs: NOW - DAY, endMs: NOW - DAY + 60_000 };
    expect(selectionProblem(sel, [span(sel.startMs, sel.startMs + 20_000)])).toBeNull();
  });
});

describe("clipRangeSeconds", () => {
  it("rounds outwards so the marked moment is always inside the delivered clip", () => {
    expect(clipRangeSeconds({ startMs: 1_000_400, endMs: 2_000_100 })).toEqual({
      startSec: 1_000,
      endSec: 2_001,
    });
  });

  it("leaves whole seconds untouched", () => {
    expect(clipRangeSeconds({ startMs: 1_000_000, endMs: 2_000_000 })).toEqual({
      startSec: 1_000,
      endSec: 2_000,
    });
  });
});

describe("clipFileName", () => {
  it("stamps the clip's LOCAL start time and its length", () => {
    const start = new Date(2026, 7, 9, 14, 3, 5).getTime();
    expect(clipFileName("big_room", { startMs: start, endMs: start + 50_000 })).toBe(
      "big_room-2026-08-09-14-03-05-50s.mp4",
    );
  });

  it("strips anything that has no business in a filename", () => {
    const start = new Date(2026, 7, 9, 1, 2, 3).getTime();
    const name = clipFileName("../front door", { startMs: start, endMs: start + 10_000 });
    expect(name).toBe("front-door-2026-08-09-01-02-03-10s.mp4");
  });
});

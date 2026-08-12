import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Timeline24h } from "../Timeline24h";
import type { CameraEvent } from "../../../lib/cameraEvents";

// The track is 1000px wide (test setup pins getBoundingClientRect) and opens at
// the default ~8h span centered on `now`, so px↔time math is deterministic.

const NOW = 1_700_000_000_000;
const DAY = 24 * 3600_000;

const clip = (id: string, startMs: number): CameraEvent => ({
  id,
  camera: "gate",
  label: "motion",
  startMs,
  endMs: null,
  hasClip: true,
  hasSnapshot: false,
  thumbnailUrl: null,
  snapshotUrl: null,
  description: null,
});

// rAF isn't implemented in jsdom — queue callbacks and flush them like frames,
// so the one-emission-per-frame throttle behaves as it does in a browser.
const rafCbs = new Map<number, FrameRequestCallback>();
let rafId = 0;
function flushRaf() {
  const cbs = [...rafCbs.values()];
  rafCbs.clear();
  for (const cb of cbs) cb(0);
}
beforeEach(() => {
  rafCbs.clear();
  vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => {
    rafCbs.set(++rafId, cb);
    return rafId;
  });
  vi.stubGlobal("cancelAnimationFrame", (id: number) => {
    rafCbs.delete(id);
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

// jsdom has no PointerEvent — dispatch MouseEvents with pointer types so
// clientX survives (the handlers only optional-chain the pointer-only APIs).
function pointer(el: Element, type: string, clientX: number) {
  fireEvent(el, new MouseEvent(type, { bubbles: true, cancelable: true, clientX }));
}

function renderTimeline(overrides: Partial<Parameters<typeof Timeline24h>[0]> = {}) {
  const onSeek = vi.fn();
  const onScrub = vi.fn();
  const onLive = vi.fn();
  render(
    <Timeline24h
      events={[clip("m1", NOW - 3600_000)]}
      startMs={NOW - DAY}
      endMs={NOW}
      playhead="live"
      onSeek={onSeek}
      onScrub={onScrub}
      onLive={onLive}
      {...overrides}
    />,
  );
  // Named, because the clip-export handles are sliders too — an unnamed lookup goes ambiguous
  // the moment a selection is on screen.
  return {
    onSeek,
    onScrub,
    onLive,
    track: screen.getByRole("slider", { name: /recording timeline/i }),
  };
}

describe("Timeline24h scrubbing", () => {
  it("streams onScrub while dragging, then commits once with onSeek on release", () => {
    const { onSeek, onScrub, track } = renderTimeline();

    pointer(track, "pointerdown", 500);
    // Drag right (positive dx) → pan back in time; each frame flush emits.
    pointer(track, "pointermove", 600);
    flushRaf();
    pointer(track, "pointermove", 700);
    flushRaf();
    expect(onScrub).toHaveBeenCalledTimes(2);
    for (const [ms] of onScrub.mock.calls) {
      expect(ms).toBeLessThan(NOW);
      expect(ms).toBeGreaterThan(NOW - DAY);
    }

    pointer(track, "pointerup", 700);
    expect(onSeek).toHaveBeenCalledTimes(1);
    // The commit is the time under the center playhead — same as the last scrub.
    const lastScrub = onScrub.mock.calls.at(-1)![0] as number;
    expect(onSeek.mock.calls[0][0]).toBe(lastScrub);
  });

  it("a movement under the tap slop is a tap: onSeek only, no onScrub", () => {
    const { onSeek, onScrub, track } = renderTimeline();

    pointer(track, "pointerdown", 300);
    pointer(track, "pointermove", 302);
    pointer(track, "pointerup", 302);

    expect(onScrub).not.toHaveBeenCalled();
    expect(onSeek).toHaveBeenCalledTimes(1);
    // Tapped left of center while live → a past time.
    expect(onSeek.mock.calls[0][0]).toBeLessThan(NOW);
  });

  it("clamps scrub emissions to now, and a release in the Live region fires onLive", () => {
    // Start scrubbed into the past so there's Live region to drag into.
    const { onSeek, onScrub, onLive, track } = renderTimeline({
      playhead: NOW - 3600_000,
    });

    pointer(track, "pointerdown", 500);
    // Drag left (negative dx) → pan forward, well past now.
    pointer(track, "pointermove", -600);
    flushRaf();
    expect(onScrub).toHaveBeenCalled();
    for (const [ms] of onScrub.mock.calls) {
      expect(ms).toBeLessThanOrEqual(NOW);
    }
    pointer(track, "pointerup", -600);

    expect(onLive).toHaveBeenCalledTimes(1);
    expect(onSeek).not.toHaveBeenCalled();
  });

  it("tapping a recording block seeks to its start", () => {
    const start = NOW - 3600_000;
    const { onSeek, onScrub } = renderTimeline({ events: [clip("m1", start)] });

    fireEvent.click(screen.getByRole("button", { name: /motion at/ }));
    expect(onSeek).toHaveBeenCalledWith(start);
    expect(onScrub).not.toHaveBeenCalled();
  });
});

describe("Timeline24h clip selection", () => {
  // The viewport opens at a 1h span over a 1000px track (3.6s per px) centred on `now`, so a
  // selection has to sit within ~30 min of now to be on screen at all — and a handle you can't
  // see is a handle you can't press.
  const SELECTION = { startMs: NOW - 600_000, endMs: NOW - 540_000 };
  const BOUNDS = { startMs: NOW - DAY, endMs: NOW - 60_000 };
  /** Where a given time lands on the track, by the same centre-anchored math the component uses. */
  const xOf = (ms: number) => 500 + (ms - NOW) / 3_600;

  it("renders nothing selection-related until a selection is passed", () => {
    renderTimeline();
    expect(screen.queryByRole("slider", { name: /clip start/i })).toBeNull();
  });

  it("exposes both handles with the time they sit at", () => {
    renderTimeline({ selection: SELECTION, selectionBounds: BOUNDS });
    const start = screen.getByRole("slider", { name: /clip start/i });
    const end = screen.getByRole("slider", { name: /clip end/i });
    expect(start).toHaveAttribute("aria-valuenow", String(SELECTION.startMs));
    expect(end).toHaveAttribute("aria-valuenow", String(SELECTION.endMs));
  });

  it("dragging a handle moves that edge and never seeks the player", () => {
    // The whole point: a handle drag is not a scrub. If it fell through to the pan/commit path,
    // finishing a drag would jump playback to wherever the handle was released.
    const onSelectionChange = vi.fn();
    const { onSeek, onScrub, onLive } = renderTimeline({
      selection: SELECTION,
      selectionBounds: BOUNDS,
      onSelectionChange,
    });
    const end = screen.getByRole("slider", { name: /clip end/i });

    const from = xOf(SELECTION.endMs);
    pointer(end, "pointerdown", from);
    pointer(end, "pointermove", from + 30);
    flushRaf();
    pointer(end, "pointerup", from + 30);

    expect(onSelectionChange).toHaveBeenCalled();
    const moved = onSelectionChange.mock.calls.at(-1)![0];
    // The held edge moved later; the other one stayed exactly where it was.
    expect(moved.endMs).toBeGreaterThan(SELECTION.endMs);
    expect(moved.startMs).toBe(SELECTION.startMs);
    expect(onSeek).not.toHaveBeenCalled();
    expect(onScrub).not.toHaveBeenCalled();
    expect(onLive).not.toHaveBeenCalled();
  });

  it("still pans and seeks normally when the press misses the handles", () => {
    const onSelectionChange = vi.fn();
    const { onSeek, track } = renderTimeline({
      selection: SELECTION,
      selectionBounds: BOUNDS,
      onSelectionChange,
    });
    pointer(track, "pointerdown", 200);
    pointer(track, "pointermove", 300);
    flushRaf();
    pointer(track, "pointerup", 300);
    expect(onSeek).toHaveBeenCalledTimes(1);
    expect(onSelectionChange).not.toHaveBeenCalled();
  });
});

/**
 * The track and both clip handles have carried `role="slider"` + `tabIndex={0}` +
 * `aria-valuenow` since they shipped, with no key handling anywhere in the file. Tab landed on
 * three controls a screen reader announces as operable and nothing answered.
 */
describe("Timeline24h keyboard", () => {
  it("steps the playhead back and forth with the arrow keys", () => {
    const { onSeek, track } = renderTimeline({ playhead: NOW - 3600_000 });

    fireEvent.keyDown(track, { key: "ArrowLeft" });
    expect(onSeek).toHaveBeenCalledTimes(1);
    const back = onSeek.mock.calls[0][0] as number;
    expect(back).toBeLessThan(NOW - 3600_000);

    onSeek.mockClear();
    fireEvent.keyDown(track, { key: "ArrowRight" });
    const forward = onSeek.mock.calls[0][0] as number;
    expect(forward).toBeGreaterThan(NOW - 3600_000);
  });

  it("steps further with Shift and PageUp/PageDown than with a bare arrow", () => {
    const from = NOW - 6 * 3600_000;
    const { onSeek, track } = renderTimeline({ playhead: from });

    fireEvent.keyDown(track, { key: "ArrowLeft" });
    const fine = from - (onSeek.mock.calls[0][0] as number);
    onSeek.mockClear();
    fireEvent.keyDown(track, { key: "ArrowLeft", shiftKey: true });
    const coarse = from - (onSeek.mock.calls[0][0] as number);
    onSeek.mockClear();
    fireEvent.keyDown(track, { key: "PageDown" });
    const page = from - (onSeek.mock.calls[0][0] as number);

    expect(coarse).toBeGreaterThan(fine);
    expect(page).toBe(coarse);
  });

  it("Home goes to the start of the window and End snaps back to live", () => {
    const { onSeek, onLive, track } = renderTimeline({ playhead: NOW - 3600_000 });

    fireEvent.keyDown(track, { key: "Home" });
    expect(onSeek).toHaveBeenCalledWith(NOW - DAY);

    // End means "now", and the commit grammar already treats at-or-past-now as the Live region.
    fireEvent.keyDown(track, { key: "End" });
    expect(onLive).toHaveBeenCalled();
  });

  it("ignores keys it has no business claiming", () => {
    const { onSeek, onLive, track } = renderTimeline({ playhead: NOW - 3600_000 });
    fireEvent.keyDown(track, { key: "a" });
    fireEvent.keyDown(track, { key: "Tab" });
    expect(onSeek).not.toHaveBeenCalled();
    expect(onLive).not.toHaveBeenCalled();
  });

  it("nudges a clip handle without also scrubbing the playhead", () => {
    const selection = { startMs: NOW - 3600_000, endMs: NOW - 3570_000 };
    const onSelectionChange = vi.fn();
    const { onSeek } = renderTimeline({
      playhead: NOW - 3600_000,
      selection,
      selectionBounds: { startMs: NOW - DAY, endMs: NOW - 60_000 },
      onSelectionChange,
    });

    const startHandle = screen.getByRole("slider", { name: "Clip start" });
    fireEvent.keyDown(startHandle, { key: "ArrowLeft" });

    // ±1s — the same NUDGE_FINE_MS the export bar's buttons use.
    expect(onSelectionChange).toHaveBeenCalledTimes(1);
    expect(onSelectionChange.mock.calls[0][0].startMs).toBe(selection.startMs - 1_000);
    // The handles sit INSIDE the track: without stopPropagation the same press would move the
    // playhead too, sliding the thing being measured against out from under the measurement.
    expect(onSeek).not.toHaveBeenCalled();
  });

  it("moves a clip handle by the coarse step with Shift", () => {
    const selection = { startMs: NOW - 3600_000, endMs: NOW - 3000_000 };
    const onSelectionChange = vi.fn();
    renderTimeline({
      playhead: NOW - 3600_000,
      selection,
      selectionBounds: { startMs: NOW - DAY, endMs: NOW - 60_000 },
      onSelectionChange,
    });

    fireEvent.keyDown(screen.getByRole("slider", { name: "Clip end" }), {
      key: "ArrowRight",
      shiftKey: true,
    });
    expect(onSelectionChange.mock.calls[0][0].endMs).toBe(selection.endMs + 15_000);
  });
});

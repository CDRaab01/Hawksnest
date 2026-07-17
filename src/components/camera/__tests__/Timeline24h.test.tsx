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
  return { onSeek, onScrub, onLive, track: screen.getByRole("slider") };
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

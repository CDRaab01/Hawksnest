import { describe, it, expect } from "vitest";
import type { CameraEvent } from "../cameraEvents";
import {
  ASSUMED_CLIP_SPAN_MS,
  clipContaining,
  clipSpanEndMs,
  offsetInClipSeconds,
} from "../clipSeek";

const clip = (id: string, startMs: number, endMs: number | null = null): CameraEvent => ({
  id,
  camera: "gate",
  label: "motion",
  startMs,
  endMs,
  hasClip: true,
  hasSnapshot: false,
  thumbnailUrl: null,
  snapshotUrl: null,
});

const T0 = 1_700_000_000_000;

describe("clipSpanEndMs", () => {
  it("uses the real endMs when the event has one", () => {
    expect(clipSpanEndMs(clip("a", T0, T0 + 90_000), null, null)).toBe(T0 + 90_000);
  });

  it("uses the loaded media duration for the loaded open-ended clip", () => {
    expect(clipSpanEndMs(clip("a", T0), "a", 62_000)).toBe(T0 + 62_000);
  });

  it("ignores the loaded duration for a different clip, and non-positive durations", () => {
    expect(clipSpanEndMs(clip("a", T0), "b", 62_000)).toBe(T0 + ASSUMED_CLIP_SPAN_MS);
    expect(clipSpanEndMs(clip("a", T0), "a", 0)).toBe(T0 + ASSUMED_CLIP_SPAN_MS);
  });

  it("assumes a short span when nothing better is known", () => {
    expect(clipSpanEndMs(clip("a", T0), null, null)).toBe(T0 + ASSUMED_CLIP_SPAN_MS);
  });
});

describe("clipContaining", () => {
  const events = [clip("a", T0, T0 + 60_000), clip("b", T0 + 120_000)];

  it("finds the clip whose span contains the time (boundaries inclusive)", () => {
    expect(clipContaining(events, T0, null, null)?.id).toBe("a");
    expect(clipContaining(events, T0 + 60_000, null, null)?.id).toBe("a");
    expect(clipContaining(events, T0 + 130_000, null, null)?.id).toBe("b");
  });

  it("returns null in a gap between clips", () => {
    expect(clipContaining(events, T0 + 90_000, null, null)).toBeNull();
    // Just past b's assumed 30s span.
    expect(clipContaining(events, T0 + 120_000 + ASSUMED_CLIP_SPAN_MS + 1, null, null)).toBeNull();
  });

  it("extends containment once the loaded clip's duration is known", () => {
    const t = T0 + 120_000 + 45_000; // outside b's assumed span…
    expect(clipContaining(events, t, null, null)).toBeNull();
    expect(clipContaining(events, t, "b", 50_000)?.id).toBe("b"); // …inside its real one
  });

  it("prefers the latest-starting clip on overlap (deterministic)", () => {
    const overlapping = [clip("a", T0, T0 + 180_000), clip("b", T0 + 120_000)];
    expect(clipContaining(overlapping, T0 + 125_000, null, null)?.id).toBe("b");
  });

  it("returns null on an empty list", () => {
    expect(clipContaining([], T0, null, null)).toBeNull();
  });
});

describe("offsetInClipSeconds", () => {
  it("maps a timeline time to seconds into the clip", () => {
    expect(offsetInClipSeconds(clip("a", T0), T0 + 12_500)).toBe(12.5);
  });

  it("clamps to ≥ 0 (a time just before the clip can't seek negative)", () => {
    expect(offsetInClipSeconds(clip("a", T0), T0 - 5_000)).toBe(0);
  });
});

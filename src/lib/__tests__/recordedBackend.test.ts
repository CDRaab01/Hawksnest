import { describe, it, expect } from "vitest";
import { hasRealRecordings, recordedBackendOf } from "../recordedBackend";

describe("recordedBackendOf", () => {
  it("is ring when the camera carries a ring-mqtt event selector", () => {
    expect(recordedBackendOf({ hasRingSelector: true, hasFrigateCamera: false })).toBe("ring");
  });

  it("is frigate when Frigate knows the camera and Ring doesn't", () => {
    expect(recordedBackendOf({ hasRingSelector: false, hasFrigateCamera: true })).toBe("frigate");
  });

  it("is none when neither backend claims the camera", () => {
    expect(recordedBackendOf({ hasRingSelector: false, hasFrigateCamera: false })).toBe("none");
  });

  it("prefers ring when both claim it — the Ring path owns the retry/expiry mechanics", () => {
    expect(recordedBackendOf({ hasRingSelector: true, hasFrigateCamera: true })).toBe("ring");
  });

  // camera.front, verbatim: a Frigate camera whose base name still carries the retired Ring
  // camera's `unavailable` selector. Ring cannot answer, so it must not win.
  it("is frigate when the ring selector is registered but not reporting", () => {
    expect(
      recordedBackendOf({
        hasRingSelector: true,
        hasFrigateCamera: true,
        ringSelectorLive: false,
      }),
    ).toBe("frigate");
  });

  // A ring-mqtt restart must not cost a ring-only camera its timeline for the whole session:
  // a dead selector loses only to a backend that can actually answer.
  it("is still ring when the selector is dead and nothing else records the camera", () => {
    expect(
      recordedBackendOf({
        hasRingSelector: true,
        hasFrigateCamera: false,
        ringSelectorLive: false,
      }),
    ).toBe("ring");
  });
});

describe("hasRealRecordings", () => {
  it("is true for both NVR backends", () => {
    expect(hasRealRecordings("ring")).toBe(true);
    expect(hasRealRecordings("frigate")).toBe(true);
  });

  // This is what keeps the demo clip looping and stops the player reporting
  // "playback error" on a bundled mp4 that was never a real recording.
  it("is false with no NVR, so that source stays a loop", () => {
    expect(hasRealRecordings("none")).toBe(false);
  });
});

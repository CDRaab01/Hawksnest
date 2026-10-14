import { describe, it, expect } from "vitest";
import { applyPlayoutBuffer, BATTERY_PLAYOUT_BUFFER_MS } from "../playoutBuffer";

/**
 * The knob has two spellings across browser generations, and none at all in jsdom. The helper
 * must pick the standard one when both exist, fall back to the legacy one (in SECONDS, not ms),
 * and be a harmless no-op everywhere else — it runs inside WebRTC negotiation, where a throw
 * would cost the whole live tier.
 */
describe("applyPlayoutBuffer", () => {
  const asReceiver = (o: object) => o as unknown as RTCRtpReceiver;

  it("sets jitterBufferTarget in milliseconds when the standard property exists", () => {
    const receiver = { jitterBufferTarget: null as number | null };
    expect(applyPlayoutBuffer(asReceiver(receiver), 500)).toBe("jitterBufferTarget");
    expect(receiver.jitterBufferTarget).toBe(500);
  });

  it("prefers jitterBufferTarget over the legacy hint when a browser has both", () => {
    const receiver = { jitterBufferTarget: null as number | null, playoutDelayHint: null as number | null };
    expect(applyPlayoutBuffer(asReceiver(receiver), 500)).toBe("jitterBufferTarget");
    expect(receiver.jitterBufferTarget).toBe(500);
    expect(receiver.playoutDelayHint).toBeNull();
  });

  it("falls back to playoutDelayHint in SECONDS on older Chromium", () => {
    const receiver = { playoutDelayHint: null as number | null };
    expect(applyPlayoutBuffer(asReceiver(receiver), 500)).toBe("playoutDelayHint");
    expect(receiver.playoutDelayHint).toBe(0.5);
  });

  it("leaves a receiver with neither knob alone and does not throw (jsdom)", () => {
    const receiver = {};
    expect(applyPlayoutBuffer(asReceiver(receiver), 500)).toBeNull();
    expect(receiver).toEqual({});
  });

  it("tolerates a missing receiver and a setter that throws", () => {
    expect(applyPlayoutBuffer(undefined, 500)).toBeNull();
    expect(applyPlayoutBuffer(null, 500)).toBeNull();
    const hostile = {};
    Object.defineProperty(hostile, "jitterBufferTarget", {
      enumerable: true,
      get: () => null,
      set: () => {
        throw new RangeError("rejected");
      },
    });
    expect(() => applyPlayoutBuffer(asReceiver(hostile), 500)).not.toThrow();
    expect(applyPlayoutBuffer(asReceiver(hostile), 500)).toBeNull();
  });

  it("uses a half-second default for battery cameras", () => {
    expect(BATTERY_PLAYOUT_BUFFER_MS).toBe(500);
  });
});

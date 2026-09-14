import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render } from "@testing-library/react";
import { WebRtcPlayer } from "../WebRtcPlayer";

/**
 * HA-negotiated WebRTC is the tier a battery camera lands on when go2rtc-direct is unavailable,
 * so it must treat a `wakeable` camera's receivers exactly as `Go2rtcPlayer` does: half a second
 * of playout buffer against the Home Hub's bursty Wi-Fi hop, and the browser default (lowest
 * latency) for every wired camera.
 */

// Signaling that never answers: the receivers are configured before the offer goes out, which is
// all these specs look at.
vi.mock("../../store/connection", () => ({
  webrtcOffer: () => new Promise<never>(() => {}),
  webrtcCandidate: () => Promise.resolve(),
}));

/** A receiver that exposes the standard playout knob, unset — as a real Chrome/Firefox does. */
type FakeReceiver = { jitterBufferTarget: number | null };

/** A peer connection that never connects and records the receivers it handed out. */
class StuckPeerConnection {
  static receivers: FakeReceiver[] = [];
  connectionState = "new";
  onconnectionstatechange: (() => void) | null = null;
  ontrack: ((e: unknown) => void) | null = null;
  onicecandidate: ((e: unknown) => void) | null = null;
  addTransceiver() {
    const receiver: FakeReceiver = { jitterBufferTarget: null };
    StuckPeerConnection.receivers.push(receiver);
    return { receiver };
  }
  close() {}
  createOffer() {
    return Promise.resolve({ sdp: "offer" });
  }
  setLocalDescription() {
    return Promise.resolve();
  }
  setRemoteDescription() {
    return Promise.resolve();
  }
  addIceCandidate() {
    return Promise.resolve();
  }
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("RTCPeerConnection", StuckPeerConnection);
  StuckPeerConnection.receivers = [];
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("WebRtcPlayer — receiver playout buffer", () => {
  it("asks both receivers to hold 500 ms for a wakeable camera", () => {
    render(<WebRtcPlayer entityId="camera.front" wakeable onFail={vi.fn()} />);
    expect(StuckPeerConnection.receivers).toHaveLength(2);
    expect(StuckPeerConnection.receivers.map((r) => r.jitterBufferTarget)).toEqual([500, 500]);
  });

  it("leaves an always-on camera's receivers at the browser default", () => {
    render(<WebRtcPlayer entityId="camera.kitchen" onFail={vi.fn()} />);
    expect(StuckPeerConnection.receivers).toHaveLength(2);
    expect(StuckPeerConnection.receivers.map((r) => r.jitterBufferTarget)).toEqual([null, null]);
  });
});

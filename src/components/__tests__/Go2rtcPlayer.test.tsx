import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { Go2rtcPlayer } from "../Go2rtcPlayer";
import { go2rtcMaybeAvailable, resetGo2rtcForTest } from "../../lib/go2rtc";

/**
 * The session breaker is process-wide: one `reportGo2rtcMedia(false)` makes EVERY camera skip
 * the go2rtc tier for a minute. That is right for a dead media path and wrong for a battery camera
 * that is merely slow to wake (a Reolink behind a Home Hub takes up to ~20 s before RTSP even
 * starts). These pin the `wakeable` contract: a longer leash, an honest label, and no global
 * verdict from a wake that never completed.
 */

/** A receiver that exposes the standard playout knob, unset — as a real Chrome/Firefox does. */
type FakeReceiver = { jitterBufferTarget: number | null };

/**
 * A peer connection that never connects — the watchdog is the only thing that can end it. It
 * hands out one {@link FakeReceiver} per transceiver so a spec can see what the player did to
 * the receivers before negotiation.
 */
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

/** A signaling socket that never opens and never errors. */
class SilentWebSocket {
  static OPEN = 1;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((e: unknown) => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(_url: string) {}
  send() {}
  close() {}
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("RTCPeerConnection", StuckPeerConnection);
  vi.stubGlobal("WebSocket", SilentWebSocket);
  StuckPeerConnection.receivers = [];
  resetGo2rtcForTest();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("Go2rtcPlayer — wakeable cameras and the session breaker", () => {
  it("steps down at 8 s and trips the breaker for everyone when an always-on camera never connects", () => {
    const onFail = vi.fn();
    render(<Go2rtcPlayer src="kitchen" onFail={onFail} />);
    expect(screen.getByRole("status")).toHaveTextContent("Connecting…");

    act(() => {
      vi.advanceTimersByTime(8_000);
    });
    expect(onFail).toHaveBeenCalledTimes(1);
    // The media path is condemned session-wide: another camera now skips the tier too.
    expect(go2rtcMaybeAvailable("garage")).toBe(false);
  });

  it("waits 30 s for a wakeable camera, says so, and never blames the media path", () => {
    const onFail = vi.fn();
    render(<Go2rtcPlayer src="driveway" wakeable onFail={onFail} />);
    expect(screen.getByRole("status")).toHaveTextContent("Waking camera…");

    // Where an always-on camera would already have stepped down.
    act(() => {
      vi.advanceTimersByTime(8_000);
    });
    expect(onFail).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(22_000);
    });
    expect(onFail).toHaveBeenCalledTimes(1);
    // This camera's wake says nothing about go2rtc: the other cameras keep their best tier.
    expect(go2rtcMaybeAvailable("garage")).toBe(true);
  });
});

/**
 * The battery cameras' frames arrive in clumps over the Home Hub's Wi-Fi hop, so their receivers
 * get a half-second playout buffer. The wired cameras must NOT pay that latency: their frames
 * already arrive evenly, and a doorbell conversation wants every millisecond.
 */
describe("Go2rtcPlayer — receiver playout buffer", () => {
  it("asks both receivers to hold 500 ms for a wakeable camera", () => {
    render(<Go2rtcPlayer src="driveway" wakeable onFail={vi.fn()} />);
    // One video + one audio transceiver, both buffered so they stay in step.
    expect(StuckPeerConnection.receivers).toHaveLength(2);
    expect(StuckPeerConnection.receivers.map((r) => r.jitterBufferTarget)).toEqual([500, 500]);
  });

  it("leaves an always-on camera's receivers at the browser default", () => {
    render(<Go2rtcPlayer src="kitchen" onFail={vi.fn()} />);
    expect(StuckPeerConnection.receivers).toHaveLength(2);
    expect(StuckPeerConnection.receivers.map((r) => r.jitterBufferTarget)).toEqual([null, null]);
  });
});

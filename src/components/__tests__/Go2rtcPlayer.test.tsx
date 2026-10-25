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

/** A signaling socket the spec drives by hand: every instance is recorded so a spec can make
 *  go2rtc "answer" any attempt, and count how many attempts the player made. */
class ScriptedWebSocket extends SilentWebSocket {
  static instances: ScriptedWebSocket[] = [];
  constructor(url: string) {
    super(url);
    ScriptedWebSocket.instances.push(this);
  }
  /** go2rtc's reply when it cannot open the source — e.g. the hub's 454 while a camera wakes. */
  sourceError() {
    act(() => {
      this.onmessage?.({
        data: JSON.stringify({ type: "error", value: "webrtc/offer: streams: 454 Session Not Found" }),
      });
    });
  }
}

/**
 * go2rtc answers `{"type":"error"}` when it cannot open a camera's source. A battery camera's hub
 * does exactly that while the camera is still waking, and go2rtc does not retry — so the player
 * must. It used to ignore the message and sit on the overlay for the whole 30 s watchdog.
 */
describe("Go2rtcPlayer — go2rtc source errors", () => {
  beforeEach(() => {
    ScriptedWebSocket.instances = [];
    vi.stubGlobal("WebSocket", ScriptedWebSocket);
  });

  it("steps an always-on camera down at once, without condemning go2rtc for everyone", () => {
    const onFail = vi.fn();
    render(<Go2rtcPlayer src="kitchen" onFail={onFail} />);
    ScriptedWebSocket.instances[0].sourceError();

    expect(onFail).toHaveBeenCalledTimes(1);
    // go2rtc just answered — its signalling works. One camera's source says nothing about the rest.
    expect(go2rtcMaybeAvailable("garage")).toBe(true);
    // And no late watchdog verdict either.
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(onFail).toHaveBeenCalledTimes(1);
    expect(ScriptedWebSocket.instances).toHaveLength(1);
  });

  it("asks again a second later while a battery camera wakes, still saying so", () => {
    const onFail = vi.fn();
    render(<Go2rtcPlayer src="backyard_patio" wakeable onFail={onFail} />);
    ScriptedWebSocket.instances[0].sourceError();
    expect(ScriptedWebSocket.instances).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(ScriptedWebSocket.instances).toHaveLength(2);
    expect(onFail).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("Waking camera…");
  });

  it("gives a battery camera's retries one shared 30 s budget, then steps down once", () => {
    const onFail = vi.fn();
    render(<Go2rtcPlayer src="backyard_patio" wakeable onFail={onFail} />);

    // The hub refuses every attempt: answer each one as it opens.
    for (let i = 0; i < 40 && onFail.mock.calls.length === 0; i++) {
      ScriptedWebSocket.instances[ScriptedWebSocket.instances.length - 1].sourceError();
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
    }

    expect(onFail).toHaveBeenCalledTimes(1);
    // ~29 attempts a second apart — the budget, not a 30 s watchdog per attempt.
    expect(ScriptedWebSocket.instances.length).toBeGreaterThanOrEqual(28);
    expect(ScriptedWebSocket.instances.length).toBeLessThanOrEqual(30);
    expect(go2rtcMaybeAvailable("garage")).toBe(true);
  });

  it("gives the attempt that finally reaches the camera what is left of the budget, at least 8 s", () => {
    const onFail = vi.fn();
    render(<Go2rtcPlayer src="backyard_patio" wakeable onFail={onFail} />);

    // 25 refused attempts, one a second.
    for (let i = 0; i < 25; i++) {
      ScriptedWebSocket.instances[ScriptedWebSocket.instances.length - 1].sourceError();
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
    }
    // Attempt 26 opened at ~25 s and goes silent. Only 5 s of budget remain, but an attempt that
    // reached an awake camera still needs time to negotiate: it gets the 8 s floor.
    act(() => {
      vi.advanceTimersByTime(7_000);
    });
    expect(onFail).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(onFail).toHaveBeenCalledTimes(1);
  });
});

/**
 * Receiver-side playout buffer for cameras whose radio path is BURSTY.
 *
 * The battery Reolinks behind the Home Hub reach us over a congested Wi-Fi hop that delivers
 * frames in clumps (measured 2026-09-13: ~40 % of frames arrive < 10 ms apart, with 2–6 s gaps
 * between clumps). WebRTC renders each frame the moment it lands, so the live view blinks
 * through a clump and then freezes until the next one. Asking the receiver to hold half a second
 * of media before playout smooths those clumps into continuous motion, at the cost of half a
 * second of latency.
 *
 * That cost is paid ONLY by `wakeable` (`isBatteryCamera`) cameras: the wired cameras keep the
 * browser default (lowest latency), because their frames already arrive evenly and a doorbell
 * conversation wants every millisecond. The player applies this to the receiver of every
 * transceiver it adds, so audio and video stay in step.
 *
 * Two spellings of the same knob, newest first:
 * - `jitterBufferTarget` (ms; Chrome ≥ 100, Firefox ≥ 118) — the standard property.
 * - `playoutDelayHint` (seconds; older Chromium) — the non-standard predecessor.
 * A receiver with neither (jsdom, an old WebKit) is left alone; nothing here may ever break
 * negotiation, so the write is guarded and swallowed.
 *
 * Android has NO equivalent: `stream-webrtc-android 1.3.10` exposes no playout-delay API on
 * `org.webrtc.RtpReceiver` (verified in the AAR), so this is a documented web-only divergence —
 * see CLAUDE.md's battery-camera paragraph.
 */

/** Half a second: long enough to bridge the measured clumping, short enough to still feel live. */
export const BATTERY_PLAYOUT_BUFFER_MS = 500;

/** Which knob was set, for tests and diagnostics; `null` when the receiver offered neither. */
export type PlayoutBufferKnob = "jitterBufferTarget" | "playoutDelayHint" | null;

/**
 * Ask `receiver` to hold `ms` of media before playout. Returns the knob it used, or `null` when
 * the receiver is missing or supports neither knob. Never throws.
 */
export function applyPlayoutBuffer(receiver: RTCRtpReceiver | null | undefined, ms: number): PlayoutBufferKnob {
  if (!receiver) return null;
  // Structural view: lib.dom types `jitterBufferTarget` but not the legacy `playoutDelayHint`.
  const knobs = receiver as unknown as Record<string, unknown>;
  try {
    if ("jitterBufferTarget" in knobs) {
      knobs.jitterBufferTarget = ms;
      return "jitterBufferTarget";
    }
    if ("playoutDelayHint" in knobs) {
      knobs.playoutDelayHint = ms / 1000;
      return "playoutDelayHint";
    }
  } catch {
    // A browser that exposes the property but rejects the write (out of range, read-only) must
    // not take the stream down with it — the picture without the buffer beats no picture.
  }
  return null;
}

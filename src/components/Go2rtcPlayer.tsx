import { useEffect, useRef, useState } from "react";
import { go2rtcWsUrl, reportGo2rtcMedia } from "../lib/go2rtc";
import { applyPlayoutBuffer, BATTERY_PLAYOUT_BUFFER_MS } from "../lib/playoutBuffer";
import { go2rtcWatchdogMs, sourceErrorRetryDelayMs } from "../lib/go2rtcWake";
import { useMediaAspect } from "../lib/mediaAspect";

/**
 * How long an ICE `disconnected` may last before it counts as a failure.
 *
 * Long enough for the ordinary causes to resolve themselves (a Wi-Fi roam, a handful of dropped
 * packets), short enough that a genuinely dead stream still steps down before the frame reads as
 * frozen. Well inside the 8 s connect watchdog, which covers the never-connected case instead.
 */
const DISCONNECT_GRACE_MS = 4_000;

/**
 * Lowest-latency live view: WebRTC negotiated **directly with the dedicated
 * go2rtc** (native Ring source) over its WebSocket API, rather than through HA's
 * bundled go2rtc. go2rtc talks straight to Ring with no ring-mqtt/ffmpeg hop, so
 * first frame is typically ~1–2s. Signaling is same-origin via the nginx
 * `/go2rtc/` proxy; media is WebRTC to go2rtc's `:8555`.
 *
 * On any failure — WS error, no answer, ICE can't reach `:8555` (e.g. the §7c
 * host forwarder isn't up, or we're off the tailnet) — it calls `onFail` so
 * `LivePlayer` steps down to the HA WebRTC path. It trips the session
 * circuit-breaker (so other cameras skip this tier) **only when media never
 * connected at all**: a stream that was working and then dropped proves nothing
 * about whether the path works, and one camera's hiccup must not cost every
 * other camera its best transport. Mirrors `WebRtcPlayer`'s recvonly
 * negotiation + "Connecting…" overlay.
 */
// Connect watchdogs: 8 s for a camera that is awake (go2rtc-direct exists because it is fast), a
// shared 30 s wake budget for one that sleeps. Both live in `lib/go2rtcWake.ts` with the retry
// rule for go2rtc's `{"type":"error"}` answer, ported 1:1 to Kotlin.

export function Go2rtcPlayer({
  src,
  poster,
  muted = true,
  wakeable = false,
  onFail,
}: {
  src: string;
  poster?: string;
  /** Audio track gate. Mounts muted for autoplay policy; flipped live via the
   *  DOM property (React only applies the `muted` attribute at mount). */
  muted?: boolean;
  /**
   * The camera sleeps and must be woken to stream (`isBatteryCamera`). Gives it the 30 s wake
   * budget and re-asks go2rtc every second while the hub is still waking it (`lib/go2rtcWake.ts`),
   * labels the overlay honestly, gives the receivers a
   * half-second playout buffer against the hub's bursty Wi-Fi (`lib/playoutBuffer.ts`), and —
   * most importantly — keeps a slow or failed wake from tripping the SESSION breaker: one
   * battery camera taking its time says nothing about whether go2rtc's media path works for the
   * other eleven.
   */
  wakeable?: boolean;
  onFail: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  // Size to the stream's true shape (a dual-lens Reolink is ~32:9) — see mediaAspect.
  const { style: aspectStyle, onVideoMeta } = useMediaAspect();
  const onFailRef = useRef(onFail);
  onFailRef.current = onFail;
  const [connecting, setConnecting] = useState(true);
  // Wake retries (lib/go2rtcWake.ts). The budget runs from the FIRST attempt of an open: a retry
  // keeps `startedAt`, anything else that (re)starts the effect — a new src — begins a fresh one.
  const [retry, setRetry] = useState(0);
  const wakeRef = useRef({ startedAt: 0, retryPending: false });

  useEffect(() => {
    if (videoRef.current) videoRef.current.muted = muted;
  }, [muted]);

  useEffect(() => {
    if (typeof RTCPeerConnection === "undefined" || typeof WebSocket === "undefined") {
      onFailRef.current();
      return;
    }

    const now = Date.now();
    const wake = wakeRef.current;
    if (!wake.retryPending) wake.startedAt = now;
    wake.retryPending = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;

    let cancelled = false;
    let pc: RTCPeerConnection | null = new RTCPeerConnection();
    const ws = new WebSocket(go2rtcWsUrl(src));
    // True once media has actually flowed. It gates the SESSION circuit-breaker below.
    let everConnected = false;
    // Pending grace timer for a `disconnected` that may still recover.
    let recovery: ReturnType<typeof setTimeout> | null = null;
    const clearRecovery = () => {
      if (recovery !== null) {
        clearTimeout(recovery);
        recovery = null;
      }
    };

    const fail = () => {
      if (cancelled) return;
      cancelled = true;
      clearRecovery();
      // Trip the breaker only for a session that never reached `connected`.
      //
      // `reportGo2rtcMedia(false)` is not local — it makes EVERY camera skip the go2rtc tier for
      // the rest of the session. That is right when media is genuinely unreachable (the §7c
      // :8555 host forwarder is down, or we're off the tailnet) and wrong when a stream that was
      // working simply dropped, which says nothing about whether the path works. Nor does a
      // camera that never woke up (`wakeable`): that is a fact about one sleeping camera.
      if (!everConnected && !wakeable) reportGo2rtcMedia(false);
      onFailRef.current();
    };

    // go2rtc answered `{"type":"error"}`: it is up, but could not open this camera's source — for
    // a battery camera, typically the hub's `454 Session Not Found` while it wakes. No answer will
    // follow. This used to be ignored, leaving "Waking camera…" up until the 30 s watchdog. Not a
    // `fail()`: go2rtc just answered, so this is never a verdict on its media path.
    const sourceError = () => {
      if (cancelled) return;
      cancelled = true;
      clearRecovery();
      clearTimeout(watchdog);
      const wait = sourceErrorRetryDelayMs(wakeable, Date.now() - wake.startedAt);
      if (wait === null) {
        onFailRef.current();
        return;
      }
      retryTimer = setTimeout(() => {
        wake.retryPending = true;
        setRetry((n) => n + 1);
      }, wait);
    };

    const video = pc.addTransceiver("video", { direction: "recvonly" });
    const audio = pc.addTransceiver("audio", { direction: "recvonly" });
    // A battery camera's frames arrive in clumps over the hub's Wi-Fi hop; half a second of
    // playout buffer turns blink-then-freeze into motion. Wired cameras keep the lowest-latency
    // default — see `lib/playoutBuffer.ts`.
    if (wakeable) {
      applyPlayoutBuffer(video?.receiver, BATTERY_PLAYOUT_BUFFER_MS);
      applyPlayoutBuffer(audio?.receiver, BATTERY_PLAYOUT_BUFFER_MS);
    }
    pc.ontrack = (e) => {
      if (videoRef.current && e.streams[0]) videoRef.current.srcObject = e.streams[0];
    };
    pc.onicecandidate = (e) => {
      if (e.candidate && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "webrtc/candidate", value: e.candidate.candidate }));
      }
    };
    pc.onconnectionstatechange = () => {
      const s = pc?.connectionState;
      if (s === "connected") {
        everConnected = true;
        clearRecovery();
        reportGo2rtcMedia(true);
        return;
      }
      // `disconnected` is a WARNING, not a verdict. ICE reports it on ordinary transient loss —
      // a Wi-Fi roam, a few dropped packets — and recovers to `connected` on its own. Treating
      // it as terminal meant one hiccup on one camera stepped that camera down the ladder AND
      // tripped the session-wide breaker, so every other camera lost the best live tier too,
      // until reload. Give it a moment to come back; `failed`/`closed` are terminal and don't.
      if (s === "disconnected") {
        if (recovery === null) {
          recovery = setTimeout(() => {
            recovery = null;
            if (pc?.connectionState !== "connected") fail();
          }, DISCONNECT_GRACE_MS);
        }
        return;
      }
      if (s === "failed" || s === "closed") fail();
    };

    ws.onmessage = (ev) => {
      let msg: { type?: string; value?: string };
      try {
        msg = JSON.parse(ev.data as string);
      } catch {
        return;
      }
      if (cancelled || !pc) return;
      if (msg.type === "webrtc/answer" && msg.value) {
        void pc.setRemoteDescription({ type: "answer", sdp: msg.value }).catch(fail);
      } else if (msg.type === "webrtc/candidate" && msg.value) {
        void pc.addIceCandidate({ candidate: msg.value, sdpMid: "0" }).catch(() => {});
      } else if (msg.type === "error") {
        sourceError();
      }
    };
    ws.onerror = fail;
    ws.onopen = async () => {
      try {
        const offer = await pc!.createOffer();
        await pc!.setLocalDescription(offer);
        ws.send(JSON.stringify({ type: "webrtc/offer", value: offer.sdp ?? "" }));
      } catch {
        fail();
      }
    };

    // Watchdog: go2rtc-direct is meant to be fast; if it hasn't connected in 8s,
    // step down rather than hang (covers unreachable media / a stale stream). A camera that
    // has to wake first gets what is left of its wake budget — see go2rtcWatchdogMs.
    const watchdog = setTimeout(
      () => {
        if (pc && pc.connectionState !== "connected") fail();
      },
      go2rtcWatchdogMs(wakeable, now - wake.startedAt),
    );

    setConnecting(true);
    return () => {
      cancelled = true;
      clearTimeout(watchdog);
      // A retry scheduled for THIS attempt must not fire into the next src's.
      if (retryTimer !== null) clearTimeout(retryTimer);
      clearRecovery();
      ws.close();
      pc?.close();
      pc = null;
    };
  }, [src, wakeable, retry]);

  return (
    <div className="relative">
      <video
        ref={videoRef}
        autoPlay
        muted
        playsInline
        poster={poster}
        onPlaying={() => setConnecting(false)}
        onLoadedMetadata={onVideoMeta}
        aria-label="Live camera view"
        className="w-full rounded-lg bg-black object-contain"
        style={aspectStyle}
      />
      {connecting && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center rounded-lg bg-black/40">
          <div className="flex items-center gap-sm rounded-full bg-panel-high/90 px-lg py-sm backdrop-blur">
            <span
              aria-hidden="true"
              className="h-3 w-3 rounded-full bg-effort animate-breathe motion-reduce:animate-none"
            />
            <span className="caption-label text-ink" role="status">
              {wakeable ? "Waking camera…" : "Connecting…"}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

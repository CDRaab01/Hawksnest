import { useEffect, useRef, useState } from "react";
import { webrtcOffer, webrtcCandidate } from "../store/connection";

/**
 * Low-latency live view over WebRTC, negotiated through HA's `camera/webrtc/offer`
 * (served by go2rtc — ring-mqtt's streaming backend). Dependency-free: a native
 * `RTCPeerConnection` receives the camera's media; the SDP answer + trickle ICE
 * flow back over the HA WebSocket. On any failure it calls `onFail` so the parent
 * `LivePlayer` steps down to HLS/MJPEG. Media is UDP and connects directly to
 * go2rtc via ICE (it does not traverse nginx) — fine on a LAN; off-LAN falls back.
 */
export function WebRtcPlayer({
  entityId,
  poster,
  onFail,
}: {
  entityId: string;
  poster?: string;
  onFail: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  // Keep onFail current without re-running the negotiation effect on each render.
  const onFailRef = useRef(onFail);
  onFailRef.current = onFail;
  // True until the first decoded frame plays — drives the "Connecting…" overlay
  // so a battery camera's multi-second wake reads as progress, not a hang.
  const [connecting, setConnecting] = useState(true);

  useEffect(() => {
    if (typeof RTCPeerConnection === "undefined") {
      onFailRef.current();
      return;
    }

    let cancelled = false;
    let pc: RTCPeerConnection | null = new RTCPeerConnection();
    let sessionId: string | null = null;
    let unsub: (() => void) | undefined;
    const pendingCandidates: RTCIceCandidateInit[] = [];

    const fail = () => {
      if (cancelled) return;
      cancelled = true;
      onFailRef.current();
    };

    pc.addTransceiver("video", { direction: "recvonly" });
    pc.addTransceiver("audio", { direction: "recvonly" });
    pc.ontrack = (e) => {
      if (videoRef.current && e.streams[0]) videoRef.current.srcObject = e.streams[0];
    };
    pc.onicecandidate = (e) => {
      if (!e.candidate) return;
      const cand = e.candidate.toJSON();
      if (sessionId) void webrtcCandidate(entityId, sessionId, cand).catch(() => {});
      else pendingCandidates.push(cand); // hold until HA gives us the session id
    };
    pc.onconnectionstatechange = () => {
      const s = pc?.connectionState;
      if (s === "failed" || s === "disconnected" || s === "closed") fail();
    };

    void (async () => {
      try {
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        const { unsubscribe } = await webrtcOffer(entityId, offer.sdp ?? "", (sig) => {
          if (cancelled || !pc) return;
          if (sig.type === "session" && sig.session_id) {
            sessionId = sig.session_id;
            for (const c of pendingCandidates.splice(0)) {
              void webrtcCandidate(entityId, sessionId, c).catch(() => {});
            }
          } else if (sig.type === "answer" && sig.answer) {
            void pc.setRemoteDescription({ type: "answer", sdp: sig.answer }).catch(fail);
          } else if (sig.type === "candidate" && sig.candidate) {
            const init =
              typeof sig.candidate === "string" ? { candidate: sig.candidate } : sig.candidate;
            void pc.addIceCandidate(init).catch(() => {});
          } else if (sig.type === "error") {
            fail();
          }
        });
        if (cancelled) unsubscribe();
        else unsub = unsubscribe;
      } catch {
        fail();
      }
    })();

    // Watchdog: if we haven't connected in 20s, fall back rather than hang.
    // 20s (not 10) because a battery camera has to wake from sleep before it
    // can negotiate, which takes longer than 10s — cutting to HLS too early
    // just trades one black screen for a slower one (mirrors the Android
    // player's deliberate 20s).
    const watchdog = setTimeout(() => {
      if (pc && pc.connectionState !== "connected") fail();
    }, 20_000);

    setConnecting(true);
    return () => {
      cancelled = true;
      clearTimeout(watchdog);
      unsub?.();
      pc?.close();
      pc = null;
    };
  }, [entityId]);

  return (
    <div className="relative">
      <video
        ref={videoRef}
        autoPlay
        muted
        playsInline
        poster={poster}
        onPlaying={() => setConnecting(false)}
        aria-label="Live camera view"
        className="aspect-video w-full rounded-lg bg-black object-contain"
      />
      {connecting && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center rounded-lg bg-black/40">
          <div className="flex items-center gap-sm rounded-full bg-panel-high/90 px-lg py-sm backdrop-blur">
            <span
              aria-hidden="true"
              className="h-3 w-3 rounded-full bg-effort animate-breathe motion-reduce:animate-none"
            />
            <span className="caption-label text-ink" role="status">
              Connecting…
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

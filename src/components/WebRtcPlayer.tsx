import { useEffect, useRef } from "react";
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
      if (sessionId) void webrtcCandidate(sessionId, cand).catch(() => {});
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
              void webrtcCandidate(sessionId, c).catch(() => {});
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

    // Watchdog: if we haven't connected in 10s, fall back rather than hang.
    const watchdog = setTimeout(() => {
      if (pc && pc.connectionState !== "connected") fail();
    }, 10_000);

    return () => {
      cancelled = true;
      clearTimeout(watchdog);
      unsub?.();
      pc?.close();
      pc = null;
    };
  }, [entityId]);

  return (
    <video
      ref={videoRef}
      autoPlay
      muted
      playsInline
      poster={poster}
      aria-label="Live camera view"
      className="aspect-video w-full rounded-lg bg-black object-contain"
    />
  );
}

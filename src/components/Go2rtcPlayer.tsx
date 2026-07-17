import { useEffect, useRef, useState } from "react";
import { go2rtcWsUrl, reportGo2rtcMedia } from "../lib/go2rtc";

/**
 * Lowest-latency live view: WebRTC negotiated **directly with the dedicated
 * go2rtc** (native Ring source) over its WebSocket API, rather than through HA's
 * bundled go2rtc. go2rtc talks straight to Ring with no ring-mqtt/ffmpeg hop, so
 * first frame is typically ~1–2s. Signaling is same-origin via the nginx
 * `/go2rtc/` proxy; media is WebRTC to go2rtc's `:8555`.
 *
 * On any failure — WS error, no answer, ICE can't reach `:8555` (e.g. the §7c
 * host forwarder isn't up, or we're off the tailnet) — it calls `onFail` so
 * `LivePlayer` steps down to the HA WebRTC path, and trips the session
 * circuit-breaker so other cameras skip this tier. Mirrors `WebRtcPlayer`'s
 * recvonly negotiation + "Connecting…" overlay.
 */
export function Go2rtcPlayer({
  src,
  poster,
  onFail,
}: {
  src: string;
  poster?: string;
  onFail: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const onFailRef = useRef(onFail);
  onFailRef.current = onFail;
  const [connecting, setConnecting] = useState(true);

  useEffect(() => {
    if (typeof RTCPeerConnection === "undefined" || typeof WebSocket === "undefined") {
      onFailRef.current();
      return;
    }

    let cancelled = false;
    let pc: RTCPeerConnection | null = new RTCPeerConnection();
    const ws = new WebSocket(go2rtcWsUrl(src));

    const fail = () => {
      if (cancelled) return;
      cancelled = true;
      reportGo2rtcMedia(false);
      onFailRef.current();
    };

    pc.addTransceiver("video", { direction: "recvonly" });
    pc.addTransceiver("audio", { direction: "recvonly" });
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
      if (s === "connected") reportGo2rtcMedia(true);
      else if (s === "failed" || s === "disconnected" || s === "closed") fail();
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
    // step down rather than hang (covers unreachable media / a stale stream).
    const watchdog = setTimeout(() => {
      if (pc && pc.connectionState !== "connected") fail();
    }, 8_000);

    setConnecting(true);
    return () => {
      cancelled = true;
      clearTimeout(watchdog);
      ws.close();
      pc?.close();
      pc = null;
    };
  }, [src]);

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

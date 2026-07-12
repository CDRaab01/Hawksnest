import { useEffect, useRef, useState } from "react";
import { Mic, MicOff } from "lucide-react";

/**
 * Push-to-talk for a Ring camera, over the DEDICATED go2rtc (native `ring:`
 * source, two-way capable — see hawksnest-automation §7c). Hold the button to
 * open a WebRTC session that sends the phone mic to the camera's back-channel;
 * release to end it. The live video keeps playing on its own (this only adds the
 * upstream audio), so we negotiate a sendonly-audio peer connection.
 *
 * Signaling rides go2rtc's WebSocket API (`/go2rtc/api/ws?src=<stream>`, proxied
 * same-origin by the app's nginx); the media is UDP/TCP straight to go2rtc's
 * :8555 host port. `src` is the go2rtc stream name, which we keep equal to the HA
 * camera base (`camera.<base>` → `<base>`).
 *
 * Browsers only expose the mic in a secure context (HTTPS / localhost); when it's
 * unavailable the button disables itself rather than failing on press.
 */
type State = "idle" | "connecting" | "talking" | "error";

function wsUrl(src: string): string {
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${window.location.host}/go2rtc/api/ws?src=${encodeURIComponent(src)}`;
}

export function TalkButton({ src }: { src: string }) {
  const [state, setState] = useState<State>("idle");
  const micSupported =
    typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;

  // Live session handles, torn down on release/unmount.
  const pcRef = useRef<RTCPeerConnection | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  function stop() {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    wsRef.current?.close();
    wsRef.current = null;
    pcRef.current?.close();
    pcRef.current = null;
    setState((s) => (s === "error" ? s : "idle"));
  }

  // Always tear down if the component unmounts mid-talk.
  useEffect(() => stop, []);

  async function start() {
    if (!micSupported || pcRef.current) return;
    setState("connecting");
    try {
      const mic = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = mic;

      const pc = new RTCPeerConnection();
      pcRef.current = pc;
      // Send the mic up; we don't need the camera's media back for talk.
      for (const track of mic.getTracks()) pc.addTrack(track, mic);

      const ws = new WebSocket(wsUrl(src));
      wsRef.current = ws;

      pc.onicecandidate = (e) => {
        if (e.candidate && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "webrtc/candidate", value: e.candidate.candidate }));
        }
      };
      pc.onconnectionstatechange = () => {
        const s = pc.connectionState;
        if (s === "connected") setState("talking");
        else if (s === "failed" || s === "disconnected" || s === "closed") {
          setState("error");
          stop();
        }
      };

      ws.onmessage = (ev) => {
        let msg: { type?: string; value?: string };
        try {
          msg = JSON.parse(ev.data as string);
        } catch {
          return;
        }
        if (msg.type === "webrtc/answer" && msg.value) {
          void pc.setRemoteDescription({ type: "answer", sdp: msg.value }).catch(() => {
            setState("error");
            stop();
          });
        } else if (msg.type === "webrtc/candidate" && msg.value) {
          void pc.addIceCandidate({ candidate: msg.value, sdpMid: "0" }).catch(() => {});
        }
      };
      ws.onerror = () => {
        setState("error");
        stop();
      };
      ws.onopen = async () => {
        try {
          const offer = await pc.createOffer();
          await pc.setLocalDescription(offer);
          ws.send(JSON.stringify({ type: "webrtc/offer", value: offer.sdp ?? "" }));
        } catch {
          setState("error");
          stop();
        }
      };
    } catch {
      // getUserMedia denied / no device, or PC setup failed.
      setState("error");
      stop();
    }
  }

  const active = state === "talking" || state === "connecting";
  const label =
    !micSupported
      ? "Talk needs HTTPS"
      : state === "talking"
        ? "Talking…"
        : state === "connecting"
          ? "Connecting…"
          : state === "error"
            ? "Talk failed"
            : "Hold to talk";

  return (
    <button
      type="button"
      disabled={!micSupported}
      // Press-and-hold: pointer events cover mouse + touch; key handlers for a11y.
      onPointerDown={start}
      onPointerUp={stop}
      onPointerLeave={() => active && stop()}
      onPointerCancel={stop}
      aria-label={micSupported ? "Hold to talk to the camera" : "Talk requires HTTPS"}
      aria-pressed={state === "talking"}
      className={[
        "relative flex items-center gap-xs rounded-sm px-sm py-xs caption-label transition-colors duration-fast select-none",
        !micSupported
          ? "bg-panel text-ink-faint cursor-not-allowed"
          : active
            ? "bg-recovery text-recovery-on"
            : state === "error"
              ? "bg-streak-dim text-streak"
              : "bg-panel text-ink-dim hover:text-ink",
      ].join(" ")}
    >
      {/* Transmit ring: while the mic is live, a ring expands from the button so
          "am I broadcasting into the yard?" is never ambiguous. */}
      {state === "talking" && (
        <span
          aria-hidden="true"
          className="absolute inset-0 animate-ping rounded-sm bg-recovery opacity-30 motion-reduce:hidden"
        />
      )}
      {micSupported ? (
        <Mic size={14} className={state === "talking" ? "animate-pulse" : undefined} />
      ) : (
        <MicOff size={14} />
      )}
      {label}
    </button>
  );
}

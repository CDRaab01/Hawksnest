import { useEffect, useState } from "react";
import { VideoOff } from "lucide-react";
import type { HassEntity } from "../lib/ha";
import { useHaBaseUrl } from "../store/entityStore";
import { streamUrl as fetchStreamUrl, supportsWebRtc } from "../store/connection";
import {
  streamUrl as mjpegUrl,
  snapshotUrlAt,
  snapshotUrl,
} from "../lib/cameraUrl";
import { HlsPlayer } from "./HlsPlayer";
import { WebRtcPlayer } from "./WebRtcPlayer";

/**
 * On-demand live view for one camera, with a graceful transport ladder:
 *
 *   WebRTC (go2rtc, <1s) → HLS/video → MJPEG proxy → snapshot poll → unavailable
 *
 * WebRTC is tried first only when the camera advertises it (`frontend_stream_type
 * === "web_rtc"`, as ring-mqtt's go2rtc cameras do) and the live source can
 * negotiate it; otherwise we start at the HLS/demo video tier. Each tier steps
 * down on error. Nothing streams in the background — this only runs while mounted
 * ("Tap to Go Live").
 */
type Mode = "webrtc" | "video" | "mjpeg" | "poll" | "dead";

export function LivePlayer({ entity }: { entity: HassEntity }) {
  const baseUrl = useHaBaseUrl();
  const mjpeg = mjpegUrl(entity, baseUrl);
  const hasSnapshot = snapshotUrl(entity, baseUrl) !== null;
  const canWebRtc =
    entity.attributes.frontend_stream_type === "web_rtc" && supportsWebRtc();

  const [src, setSrc] = useState<string | null>(null);
  const [srcResolved, setSrcResolved] = useState(false);
  const [mode, setMode] = useState<Mode>(canWebRtc ? "webrtc" : "video");
  const [tick, setTick] = useState(0);

  // Resolve the HLS/demo stream URL on mount (used by the "video" tier, and the
  // fallback target when WebRTC drops). Resets when the camera changes.
  useEffect(() => {
    let active = true;
    setSrc(null);
    setSrcResolved(false);
    setMode(canWebRtc ? "webrtc" : "video");
    fetchStreamUrl(entity.entity_id)
      .then((url) => {
        if (!active) return;
        setSrc(url);
        setSrcResolved(true);
      })
      .catch(() => {
        if (!active) return;
        setSrc(null);
        setSrcResolved(true);
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entity.entity_id]);

  // Once the stream URL resolves to nothing while we're on the video tier, step down.
  useEffect(() => {
    if (mode === "video" && srcResolved && !src) stepDownFrom("video");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, srcResolved, src]);

  // Fast snapshot polling once we've fallen back to the poll tier.
  useEffect(() => {
    if (mode !== "poll") return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [mode]);

  /** Advance the ladder one rung past `from`, skipping tiers we can't render. */
  function stepDownFrom(from: Mode) {
    if (from === "webrtc") setMode("video");
    else if (from === "video") setMode(mjpeg ? "mjpeg" : hasSnapshot ? "poll" : "dead");
    else if (from === "mjpeg") setMode(hasSnapshot ? "poll" : "dead");
    else setMode("dead");
  }

  if (mode === "dead") {
    return (
      <div className="flex aspect-video w-full flex-col items-center justify-center gap-sm rounded-lg bg-panel">
        <VideoOff className="text-ink-faint" size={40} />
        <span className="font-body text-body text-ink-dim">Live view unavailable</span>
      </div>
    );
  }

  if (mode === "webrtc") {
    return (
      <WebRtcPlayer
        entityId={entity.entity_id}
        poster={snapshotUrl(entity, baseUrl) ?? undefined}
        onFail={() => stepDownFrom("webrtc")}
      />
    );
  }

  if (mode === "video") {
    if (!src) {
      // Resolving the stream URL — hold the frame with the latest snapshot.
      return (
        <img
          src={snapshotUrl(entity, baseUrl) ?? undefined}
          alt="Live camera view"
          className="aspect-video w-full rounded-lg bg-black object-contain"
        />
      );
    }
    return (
      <HlsPlayer
        src={src}
        poster={snapshotUrl(entity, baseUrl) ?? undefined}
        loop
        onError={() => stepDownFrom("video")}
      />
    );
  }

  const imgSrc =
    mode === "mjpeg" ? (mjpeg as string) : snapshotUrlAt(entity, tick, baseUrl);

  return (
    <img
      src={imgSrc ?? undefined}
      alt="Live camera view"
      onError={() => stepDownFrom(mode)}
      className="aspect-video w-full rounded-lg bg-black object-contain"
    />
  );
}

import { useEffect, useState } from "react";
import { VideoOff } from "lucide-react";
import type { HassEntity } from "../lib/ha";
import { useHaBaseUrl } from "../store/entityStore";
import { streamUrl as fetchStreamUrl } from "../store/connection";
import {
  streamUrl as mjpegUrl,
  snapshotUrlAt,
  snapshotUrl,
} from "../lib/cameraUrl";
import { HlsPlayer } from "./HlsPlayer";

/**
 * On-demand live view for one camera, with a graceful transport ladder:
 *
 *   video/HLS (go2rtc, low-latency) → MJPEG proxy → snapshot poll → unavailable
 *
 * The top tier is the source's `streamUrl` — an HLS feed from live HA, or the
 * bundled demo clip in demo mode — rendered in a `<video>`. If that errors (or
 * the source has no stream) we drop to HA's MJPEG proxy `<img>`, then to 1fps
 * snapshot polling, then to a clear "unavailable" state. Nothing streams in the
 * background: this only runs while mounted ("Tap to Go Live" behavior).
 *
 * (WebRTC is the intended tier above HLS; it slots in here once go2rtc is on the
 * cluster — the ladder is already structured to step down from it.)
 */
type Mode = "video" | "mjpeg" | "poll" | "dead";

export function LivePlayer({ entity }: { entity: HassEntity }) {
  const baseUrl = useHaBaseUrl();
  const mjpeg = mjpegUrl(entity, baseUrl);
  const hasSnapshot = snapshotUrl(entity, baseUrl) !== null;

  // Start in a "loading the stream URL" state; resolve to video or step down.
  const [src, setSrc] = useState<string | null>(null);
  const [mode, setMode] = useState<Mode>("video");
  const [tick, setTick] = useState(0);

  // Ask the source for an HLS/demo stream URL on mount (and when the camera
  // changes). On null/failure, step straight to the MJPEG (or poll/dead) tier.
  useEffect(() => {
    let active = true;
    setSrc(null);
    setMode("video");
    fetchStreamUrl(entity.entity_id)
      .then((url) => {
        if (!active) return;
        if (url) setSrc(url);
        else stepDownFrom("video");
      })
      .catch(() => active && stepDownFrom("video"));
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entity.entity_id]);

  // Fast snapshot polling once we've fallen back to the poll tier.
  useEffect(() => {
    if (mode !== "poll") return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [mode]);

  /** Advance the ladder one rung past `from`, skipping tiers we can't render. */
  function stepDownFrom(from: Mode) {
    if (from === "video") setMode(mjpeg ? "mjpeg" : hasSnapshot ? "poll" : "dead");
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

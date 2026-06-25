import { useEffect, useState } from "react";
import { VideoOff } from "lucide-react";
import type { HassEntity } from "../lib/ha";
import { useHaBaseUrl } from "../store/entityStore";
import { streamUrl, snapshotUrlAt, snapshotUrl } from "../lib/cameraUrl";

/**
 * On-demand live view for one camera. Default path is HA's MJPEG proxy stream
 * rendered dependency-free in an `<img>` (`multipart/x-mixed-replace`). If that
 * errors (camera can't stream, or nginx buffering trips it), we fall back to
 * fast snapshot polling, then to a clear "unavailable" state. The stream `<img>`
 * is only mounted while this component is, so nothing streams in the background.
 */
export function LivePlayer({ entity }: { entity: HassEntity }) {
  const baseUrl = useHaBaseUrl();
  const mjpeg = streamUrl(entity, baseUrl);
  const [mode, setMode] = useState<"stream" | "poll" | "dead">(
    mjpeg ? "stream" : snapshotUrl(entity, baseUrl) ? "poll" : "dead",
  );
  const [tick, setTick] = useState(0);

  // Fast snapshot polling when we've fallen back from the stream.
  useEffect(() => {
    if (mode !== "poll") return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [mode]);

  if (mode === "dead") {
    return (
      <div className="flex aspect-video w-full flex-col items-center justify-center gap-sm rounded-lg bg-panel">
        <VideoOff className="text-ink-faint" size={40} />
        <span className="font-body text-body text-ink-dim">Live view unavailable</span>
      </div>
    );
  }

  const src =
    mode === "stream" ? (mjpeg as string) : snapshotUrlAt(entity, tick, baseUrl);

  return (
    <img
      src={src ?? undefined}
      alt="Live camera view"
      onError={() => setMode((m) => (m === "stream" ? "poll" : "dead"))}
      className="aspect-video w-full rounded-lg bg-black object-contain"
    />
  );
}

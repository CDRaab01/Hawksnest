import { useState } from "react";
import { Camera, VideoOff } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { useSnapshotBucket } from "../components/snapshotBucket";
import { useHaBaseUrl } from "../store/entityStore";
import { resolveName } from "../lib/resolve";
import { snapshotUrlAt, isCameraLive } from "../lib/cameraUrl";
import { relativeTime } from "../lib/relativeTime";
import type { CardProps } from "./types";

function lastChangedMs(lastChanged?: string): number | null {
  if (!lastChanged) return null;
  const t = new Date(lastChanged).getTime();
  return Number.isFinite(t) ? t : null;
}

/**
 * Camera tile. Renders Home Assistant's signed `entity_picture` snapshot, kept
 * fresh by the shared snapshot bucket. Falls back to the original IR-gradient
 * placeholder when there's no stream (demo mode, an unavailable camera, or a
 * failed fetch) so the Security scene always reads correctly.
 */
export function CameraTile({
  entity,
  overrides,
  density = "comfortable",
  name: nameOverride,
}: CardProps & { name?: string }) {
  const name = nameOverride ?? resolveName(entity, overrides);
  const aspect = density === "compact" ? "aspect-video" : "aspect-[4/3]";
  const bucket = useSnapshotBucket();
  const baseUrl = useHaBaseUrl();
  const [failed, setFailed] = useState(false);

  const live = isCameraLive(entity) && !failed;
  const src = live ? snapshotUrlAt(entity, bucket, baseUrl) : null;
  const changedMs = lastChangedMs(entity.last_changed);

  return (
    <PanelCard className="overflow-hidden">
      <div
        className={[
          aspect,
          "relative w-full bg-[radial-gradient(120%_120%_at_20%_0%,#2a2f37_0%,#0e1116_70%)]",
        ].join(" ")}
      >
        {src ? (
          <img
            key={src}
            src={src}
            alt={`${name} live snapshot`}
            loading="lazy"
            onError={() => setFailed(true)}
            className="absolute inset-0 h-full w-full object-cover"
          />
        ) : (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-xs">
            {failed ? (
              <VideoOff className="text-ink-faint" size={32} />
            ) : (
              <Camera className="text-ink-faint" size={32} />
            )}
            <span className="caption-label text-ink-faint">
              {failed ? "No signal" : "Offline"}
            </span>
          </div>
        )}

        {/* Ring-style freshness badge: a status dot + the snapshot's age. The tile
            is a periodic still (not a live feed), so we stamp how old it is rather
            than calling it "Live" — tapping the tile is what opens the live view. */}
        <div className="absolute left-md top-md flex items-center gap-xs rounded-sm bg-black/40 px-sm py-xs backdrop-blur">
          <span
            className={[
              "h-2 w-2 rounded-full",
              live ? "bg-recovery" : "bg-ink-faint",
            ].join(" ")}
          />
          <span className="caption-label text-white/90">
            {changedMs ? relativeTime(changedMs) : live ? "Live" : "—"}
          </span>
        </div>

        <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/70 to-transparent p-md">
          <span className="font-body text-body text-white">{name}</span>
        </div>
      </div>
    </PanelCard>
  );
}

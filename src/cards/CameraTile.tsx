import { useState } from "react";
import { Camera, VideoOff } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { Skeleton } from "../components/Skeleton";
import { useSnapshotBucket } from "../components/snapshotBucketContext";
import { useHaBaseUrl } from "../store/entityStore";
import { resolveName } from "../lib/resolve";
import { snapshotUrlAt, isCameraLive } from "../lib/cameraUrl";
import { relativeTime, parseHaTime } from "../lib/relativeTime";
import type { CardProps } from "./types";

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
  // The last snapshot URL that actually decoded. We keep showing it while the next
  // bucket's frame loads so the tile never blanks to black on the ~10s refresh.
  const [loaded, setLoaded] = useState<string | null>(null);

  const live = isCameraLive(entity) && !failed;
  const src = live ? snapshotUrlAt(entity, bucket, baseUrl) : null;
  const changedMs = parseHaTime(entity.last_changed);
  // Show the freshest frame we've successfully decoded; the placeholder only wins
  // when we've never loaded one (first paint, offline, or a fetch error before any frame).
  const visible = loaded ?? (failed ? null : src);

  return (
    <PanelCard className="overflow-hidden">
      <div
        className={[
          aspect,
          "relative w-full bg-[radial-gradient(120%_120%_at_20%_0%,#2a2f37_0%,#0e1116_70%)]",
        ].join(" ")}
      >
        {visible ? (
          <img
            src={visible}
            alt={`${name} live snapshot`}
            className={[
              "absolute inset-0 h-full w-full object-cover",
              // First frame: hidden under the skeleton until it decodes, then a
              // gentle reveal. Refresh swaps stay at full opacity (no flicker).
              "transition-opacity duration-emphasized ease-decel motion-reduce:transition-none",
              loaded ? "opacity-100" : "opacity-0",
            ].join(" ")}
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

        {/* First-frame loading texture: a PULSE skeleton shimmer fills the tile
            while the initial snapshot decodes over the tunnel; it never shows
            again once any frame has landed (refreshes swap seamlessly above). */}
        {live && !loaded && (
          <Skeleton
            className="absolute inset-0"
            label={`Loading ${name} snapshot`}
          />
        )}

        {/* Hidden preloader: fetch the next frame off-screen and only promote it to the
            visible <img> once it has decoded (so the swap is seamless). A refresh that
            fails leaves the last good frame in place — we only surface "No signal" if we
            never had a frame to begin with. */}
        {src && src !== loaded && (
          <img
            src={src}
            alt=""
            aria-hidden="true"
            className="hidden"
            onLoad={() => {
              setFailed(false);
              setLoaded(src);
            }}
            onError={() => {
              if (!loaded) setFailed(true);
            }}
          />
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

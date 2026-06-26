import { useEffect, useMemo, useState } from "react";
import type { HassEntity } from "../../lib/ha";
import { fetchCameraEvents, recordingUrlAt } from "../../store/connection";
import type { CameraEvent } from "../../lib/cameraEvents";
import { LivePlayer } from "../LivePlayer";
import { HlsPlayer } from "../HlsPlayer";
import { CameraSwitcher } from "./CameraSwitcher";
import { Timeline24h } from "./Timeline24h";
import { TransportBar } from "./TransportBar";

const DAY_MS = 24 * 3600_000;

/** The Frigate camera name backing a `camera.<slug>` entity. */
function cameraNameOf(entity: HassEntity): string {
  const parts = entity.entity_id.split(".");
  return parts[1] ?? entity.entity_id;
}

/**
 * Ring-style camera player: live feed + a scrubbable 24h timeline of recorded
 * events, an in-player camera switcher, and transport controls. The playhead is
 * `"live"` (the LivePlayer transport ladder) or an epoch-ms time (recorded HLS
 * VOD from the source). Events + recordings come from the active source — Frigate
 * live, or synthesized demo data playing the bundled clip.
 */
export function CameraPlayer({
  entity,
  cameras,
  onSelectCamera,
}: {
  entity: HassEntity;
  cameras: HassEntity[];
  onSelectCamera: (entity: HassEntity) => void;
}) {
  const cameraName = cameraNameOf(entity);

  // Fix the timeline window when the player opens (rolling 24h ending now).
  const [window] = useState(() => {
    const end = Date.now();
    return { start: end - DAY_MS, end };
  });

  const [events, setEvents] = useState<CameraEvent[]>([]);
  const [playhead, setPlayhead] = useState<number | "live">("live");
  const [paused, setPaused] = useState(false);

  // Re-fetch events when the camera changes; reset to live on switch.
  useEffect(() => {
    let active = true;
    setPlayhead("live");
    fetchCameraEvents(cameraName, window.start, window.end)
      .then((evs) => active && setEvents(evs))
      .catch(() => active && setEvents([]));
    return () => {
      active = false;
    };
  }, [cameraName, window.start, window.end]);

  const isLive = playhead === "live";
  const headTime = isLive ? window.end : playhead;

  // Prev = latest event before the playhead; next = earliest after it.
  const { prev, next } = useMemo(() => {
    const before = events.filter((e) => e.startMs < headTime);
    const after = events.filter((e) => e.startMs > headTime);
    return {
      prev: before.length ? before[before.length - 1] : null,
      next: after.length ? after[0] : null,
    };
  }, [events, headTime]);

  const seek = (ms: number) => {
    setPlayhead(Math.round(ms));
    setPaused(false);
  };

  const recordingSrc = isLive ? null : recordingUrlAt(cameraName, headTime, window.end);

  return (
    <div className="space-y-md">
      <div className="flex items-center justify-between gap-md">
        <CameraSwitcher cameras={cameras} current={entity} onSelect={onSelectCamera} />
        <span
          className={[
            "flex items-center gap-xs rounded-sm px-sm py-xs caption-label",
            isLive ? "text-recovery" : "text-ink-dim",
          ].join(" ")}
        >
          <span
            className={[
              "h-2 w-2 rounded-full",
              isLive ? "bg-recovery" : "bg-ink-faint",
            ].join(" ")}
          />
          {isLive ? "Live" : "Recorded"}
        </span>
      </div>

      {isLive || !recordingSrc ? (
        <LivePlayer entity={entity} />
      ) : (
        <HlsPlayer src={recordingSrc} paused={paused} loop />
      )}

      <Timeline24h
        events={events}
        startMs={window.start}
        endMs={window.end}
        playhead={playhead}
        onSeek={seek}
      />

      <TransportBar
        isLive={isLive}
        isPaused={paused}
        canPrev={prev !== null}
        canNext={next !== null || !isLive}
        onPrev={() => prev && seek(prev.startMs)}
        onNext={() => (next ? seek(next.startMs) : setPlayhead("live"))}
        onTogglePlay={() => setPaused((p) => !p)}
        onLive={() => setPlayhead("live")}
      />
    </div>
  );
}

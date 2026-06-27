import { useEffect, useMemo, useState } from "react";
import type { LogicalCamera } from "../../lib/cameraModel";
import {
  fetchCameraEvents,
  recordingUrlAt,
  streamUrl,
  callService,
} from "../../store/connection";
import { useEntity } from "../../store/entityStore";
import type { CameraEvent } from "../../lib/cameraEvents";
import { ringEventsFromSelect } from "../../lib/ringEvents";
import { LivePlayer } from "../LivePlayer";
import { HlsPlayer } from "../HlsPlayer";
import { CameraSwitcher } from "./CameraSwitcher";
import { SirenButton } from "./SirenButton";
import { TalkButton } from "./TalkButton";
import { Timeline24h } from "./Timeline24h";
import { TransportBar } from "./TransportBar";

const DAY_MS = 24 * 3600_000;

function cameraNameOf(camera: LogicalCamera): string {
  return camera.id.split(".")[1] ?? camera.id;
}

/**
 * Ring-style camera player: live feed + a scrubbable 24h timeline of recorded
 * events, an in-player camera switcher, and transport controls. The playhead is
 * `"live"` or an epoch-ms time.
 *
 * Recorded events come from one of two backends, transparently:
 * - **ring-mqtt** (`camera.eventSelectId` present): the last ~5 events from the
 *   event-selector entity; seeking snaps to an event, sets the selector
 *   (`select.select_option`), and plays the `camera.<base>_event` stream.
 * - **Frigate / demo**: `fetchCameraEvents` + a continuous `recordingUrlAt` VOD
 *   (demo synthesizes both and plays the bundled clip).
 */
export function CameraPlayer({
  camera,
  cameras,
  onSelectCamera,
}: {
  camera: LogicalCamera;
  cameras: LogicalCamera[];
  onSelectCamera: (camera: LogicalCamera) => void;
}) {
  const cameraName = cameraNameOf(camera);
  const isRing = camera.eventSelectId !== null;
  const ringSelect = useEntity(camera.eventSelectId ?? "");

  const [window] = useState(() => {
    const end = Date.now();
    return { start: end - DAY_MS, end };
  });

  const [playhead, setPlayhead] = useState<number | "live">("live");
  const [paused, setPaused] = useState(false);

  // Demo/Frigate events come from the source; ring events come off the selector.
  const [fetched, setFetched] = useState<CameraEvent[]>([]);
  useEffect(() => {
    if (isRing) return;
    let active = true;
    setPlayhead("live");
    fetchCameraEvents(cameraName, window.start, window.end)
      .then((e) => active && setFetched(e))
      .catch(() => active && setFetched([]));
    return () => {
      active = false;
    };
  }, [isRing, cameraName, window.start, window.end]);

  const events = useMemo(
    () => (isRing ? ringEventsFromSelect(ringSelect, cameraName, window.end) : fetched),
    [isRing, ringSelect, cameraName, window.end, fetched],
  );

  const isLive = playhead === "live";
  const headTime = isLive ? window.end : playhead;
  const selected = isLive ? undefined : events.find((e) => e.startMs === headTime);

  const { prev, next } = useMemo(() => {
    const before = events.filter((e) => e.startMs < headTime);
    const after = events.filter((e) => e.startMs > headTime);
    return {
      prev: before.length ? before[before.length - 1] : null,
      next: after.length ? after[0] : null,
    };
  }, [events, headTime]);

  function seek(ms: number) {
    if (isRing && events.length) {
      // No continuous VOD on ring — snap to the nearest recorded event.
      const nearest = events.reduce((best, e) =>
        Math.abs(e.startMs - ms) < Math.abs(best.startMs - ms) ? e : best,
      );
      setPlayhead(nearest.startMs);
    } else {
      setPlayhead(Math.round(Math.min(window.end, Math.max(window.start, ms))));
    }
    setPaused(false);
  }

  // ring recorded playback: select the event, then stream the `_event` camera.
  const [ringSrc, setRingSrc] = useState<string | null>(null);
  useEffect(() => {
    if (!isRing || isLive || !selected || !camera.eventSelectId || !camera.eventStreamId) {
      setRingSrc(null);
      return;
    }
    let active = true;
    setRingSrc(null);
    (async () => {
      try {
        await callService("select", "select_option", {
          entity_id: camera.eventSelectId!,
          option: selected.id,
        });
      } catch {
        /* selecting failed — still try to read whatever the event stream has */
      }
      const url = await streamUrl(camera.eventStreamId!);
      if (active) setRingSrc(url);
    })();
    return () => {
      active = false;
    };
    // selected is intentionally tracked by id only — its object identity changes
    // each render, but re-selecting the same event would re-trigger the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isRing, isLive, selected?.id, camera.eventSelectId, camera.eventStreamId]);

  const recordingSrc = isLive
    ? null
    : isRing
      ? ringSrc
      : recordingUrlAt(cameraName, headTime, window.end);

  return (
    <div className="space-y-md">
      <div className="flex items-center justify-between gap-md">
        <CameraSwitcher cameras={cameras} current={camera} onSelect={onSelectCamera} />
        <div className="flex items-center gap-sm">
          {isRing && isLive && <TalkButton src={cameraName} />}
          {camera.sirenSwitchId && <SirenButton entityId={camera.sirenSwitchId} />}
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
      </div>

      {isLive || !recordingSrc ? (
        <LivePlayer entity={camera.liveEntity} />
      ) : (
        <HlsPlayer src={recordingSrc} paused={paused} loop={!isRing} />
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

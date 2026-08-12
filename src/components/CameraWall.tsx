import { CameraOff } from "lucide-react";
import { useEntityStore, useLogicalCameras } from "../store/entityStore";
import { useCameraOverlay } from "../store/cameraOverlay";
import { overrides } from "../config/overrides";
import { isCameraLive } from "../lib/cameraUrl";
import { CameraTile } from "../cards/CameraTile";
import { SectionHeader } from "./SectionHeader";
import { PanelCard } from "./PanelCard";

/**
 * The Dashboard's camera wall — a full-width responsive grid of live snapshot
 * tiles (Ring-style). One tile per logical camera (ring-mqtt's `_live`/`_snapshot`
 * pair is collapsed upstream). Tapping a tile opens the on-demand player. Live
 * cameras sort ahead of unavailable ones so the wall reads "best first".
 */
export function CameraWall() {
  const cameras = useLogicalCameras();
  const entities = useEntityStore((s) => s.entities);
  const openCamera = useCameraOverlay((s) => s.open);

  if (cameras.length === 0) {
    return (
      <section className="space-y-md">
        <SectionHeader label="Cameras" channel="effort" />
        <PanelCard className="flex flex-col items-center justify-center gap-sm p-xl">
          <CameraOff className="text-ink-faint" size={28} />
          <span className="font-body text-body text-ink-dim">
            No cameras found in Home Assistant.
          </span>
        </PanelCard>
      </section>
    );
  }

  // Order is FIXED, not live-first.
  //
  // Sorting by availability re-ranked the grid every time a camera flipped state, which on
  // battery cameras is often — so tiles moved under a finger already on its way down and the tap
  // landed on a different camera. `resolveCameras` already sorts by id, so this is stable across
  // renders and across sessions, and the wall becomes something you can learn the shape of.
  // Which cameras are live is still said, once, in the header count.
  const liveCount = cameras.filter((c) => isCameraLive(c.snapshotEntity)).length;

  return (
    <section className="space-y-md">
      <SectionHeader
        label="Cameras"
        channel="effort"
        trailing={
          <span className="font-body text-caption text-ink-dim">
            {liveCount}/{cameras.length} live
          </span>
        }
      />
      {/* Ring-style: two side-by-side tiles on phones, more on wider screens. */}
      <div className="grid grid-cols-2 gap-sm lg:grid-cols-3 xl:grid-cols-4">
        {cameras.map((cam) => (
          <button
            key={cam.id}
            type="button"
            onClick={() => openCamera(cam.id)}
            aria-label={`Open ${cam.name} live view`}
            className="block text-left transition-transform duration-fast ease-ease active:scale-[0.98]"
          >
            <CameraTile
              entity={cam.snapshotEntity}
              overrides={overrides}
              density="compact"
              name={cam.name}
              transitionId={cam.id}
              ringing={cam.dingId !== null && entities[cam.dingId]?.state === "on"}
            />
          </button>
        ))}
      </div>
    </section>
  );
}

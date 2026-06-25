import { useState } from "react";
import { CameraOff } from "lucide-react";
import { useCameraEntities } from "../store/entityStore";
import { overrides } from "../config/overrides";
import { resolveName } from "../lib/resolve";
import { isCameraLive } from "../lib/cameraUrl";
import { CameraTile } from "../cards/CameraTile";
import { CameraLightbox } from "./CameraLightbox";
import { SectionHeader } from "./SectionHeader";
import { PanelCard } from "./PanelCard";
import type { HassEntity } from "../lib/ha";

/**
 * The Dashboard's camera wall — a full-width responsive grid of live snapshot
 * tiles (Ring-style). Tapping a tile opens the on-demand live view. Live cameras
 * sort ahead of unavailable ones so the wall reads "best first".
 */
export function CameraWall() {
  const cameras = useCameraEntities();
  const [active, setActive] = useState<HassEntity | null>(null);

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

  const ordered = [...cameras].sort(
    (a, b) => Number(isCameraLive(b)) - Number(isCameraLive(a)),
  );
  const liveCount = cameras.filter(isCameraLive).length;

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
      <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {ordered.map((entity) => (
          <button
            key={entity.entity_id}
            type="button"
            onClick={() => setActive(entity)}
            aria-label={`Open ${resolveName(entity, overrides)} live view`}
            className="block text-left transition-transform duration-fast ease-ease active:scale-[0.98]"
          >
            <CameraTile entity={entity} overrides={overrides} density="comfortable" />
          </button>
        ))}
      </div>

      {active && (
        <CameraLightbox entity={active} onClose={() => setActive(null)} />
      )}
    </section>
  );
}

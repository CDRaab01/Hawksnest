import { DoorClosed } from "lucide-react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { Skeleton } from "../components/Skeleton";
import { AreaCard } from "../components/AreaCard";
import { useEntitiesByArea, useConnection } from "../store/entityStore";

const GRID = "grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4";

/** A grid of room-card skeletons while the first registry + entities land. */
function RoomsSkeleton() {
  return (
    <div className={GRID}>
      {Array.from({ length: 6 }).map((_, i) => (
        <PanelCard key={i} className="p-lg">
          <div className="flex items-center gap-md">
            <Skeleton className="h-11 w-11 shrink-0 rounded-lg" />
            <div className="min-w-0 flex-1 space-y-xs">
              <Skeleton className="h-4 w-2/3 rounded-sm" />
              <Skeleton className="h-3 w-16 rounded-sm" />
            </div>
          </div>
        </PanelCard>
      ))}
    </div>
  );
}

/**
 * Rooms — the area hub, moved off the Dashboard so the landing screen stays glanceable. A grid of
 * area cards (name + device summary + channel tint) that drill into `/area/:area` detail, where the
 * room's device controls live. Reuses `AreaCard` and `useEntitiesByArea`.
 */
export function RoomsScreen() {
  const areas = useEntitiesByArea();
  const { status } = useConnection();
  const connecting = status === "connecting" && areas.length === 0;

  return (
    <div className="space-y-md">
      <SectionHeader label="Rooms" channel="recovery" />
      {connecting ? (
        <RoomsSkeleton />
      ) : areas.length === 0 ? (
        <PanelCard className="flex flex-col items-center justify-center gap-sm p-xl text-center">
          <DoorClosed className="text-ink-faint" size={28} />
          <span className="font-body text-body text-ink-dim">
            No rooms yet. Assign your devices to areas in Home Assistant and they'll
            appear here.
          </span>
        </PanelCard>
      ) : (
        <div className={GRID}>
          {areas.map((group, i) => (
            <AreaCard key={group.area} group={group} index={i} />
          ))}
        </div>
      )}
    </div>
  );
}

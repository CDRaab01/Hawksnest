import { SectionHeader } from "../components/SectionHeader";
import { AreaCard } from "../components/AreaCard";
import { useEntitiesByArea } from "../store/entityStore";

/**
 * Rooms — the area hub, moved off the Dashboard so the landing screen stays glanceable. A grid of
 * area cards (name + device summary + channel tint) that drill into `/area/:area` detail, where the
 * room's device controls live. Reuses `AreaCard` and `useEntitiesByArea`.
 */
export function RoomsScreen() {
  const areas = useEntitiesByArea();

  return (
    <div className="space-y-md">
      <SectionHeader label="Rooms" channel="recovery" />
      <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {areas.map((group, i) => (
          <AreaCard key={group.area} group={group} index={i} />
        ))}
      </div>
    </div>
  );
}

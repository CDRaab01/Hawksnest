import { EntityCard } from "../components/EntityCard";
import { CardLink } from "../components/CardLink";
import { SectionHeader } from "../components/SectionHeader";
import { AreaCard } from "../components/AreaCard";
import { SecurityStatusBar } from "../components/SecurityStatusBar";
import { CameraWall } from "../components/CameraWall";
import { overrides } from "../config/overrides";
import { useEntitiesByArea, useEntityStore } from "../store/entityStore";
import { useFavorites } from "../store/prefsStore";

/**
 * Dashboard — the camera-forward landing screen. Security posture up top, then a
 * full-width camera wall, the user's pinned controls, and the area hub. Uses the
 * full viewport width (the shell drops the old narrow column).
 */
export function DashboardScreen() {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntitiesByArea();
  const favorites = useFavorites();
  const pinned = favorites.map((id) => entities[id]).filter(Boolean);

  return (
    <div className="space-y-xl">
      <SecurityStatusBar />

      <CameraWall />

      {pinned.length > 0 && (
        <section className="space-y-md">
          <SectionHeader label="Home" channel="effort" />
          <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {pinned.map((entity) => (
              <CardLink
                key={entity.entity_id}
                to={`/entity/${encodeURIComponent(entity.entity_id)}`}
              >
                <EntityCard entity={entity} overrides={overrides} density="comfortable" />
              </CardLink>
            ))}
          </div>
        </section>
      )}

      <section className="space-y-md">
        <SectionHeader label="Areas" channel="recovery" />
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {areas.map((group, i) => (
            <AreaCard key={group.area} group={group} index={i} />
          ))}
        </div>
      </section>
    </div>
  );
}

import { EntityCard } from "../components/EntityCard";
import { SectionHeader } from "../components/SectionHeader";
import { AreaCard } from "../components/AreaCard";
import { overrides } from "../config/overrides";
import { favorites } from "../config/favorites";
import { useEntitiesByArea, useEntityStore } from "../store/entityStore";

/**
 * Home — the blend's landing screen: a pinned "Home" favorites section
 * (comfortable A-style cards) above the area hub (C-style cards).
 */
export function HomeScreen() {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntitiesByArea();
  const pinned = favorites.map((id) => entities[id]).filter(Boolean);

  return (
    <div className="space-y-xl">
      {pinned.length > 0 && (
        <section className="space-y-md">
          <SectionHeader label="Home" channel="effort" />
          <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
            {pinned.map((entity) => (
              <EntityCard
                key={entity.entity_id}
                entity={entity}
                overrides={overrides}
                density="comfortable"
              />
            ))}
          </div>
        </section>
      )}

      <section className="space-y-md">
        <SectionHeader label="Areas" channel="recovery" />
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
          {areas.map((group, i) => (
            <AreaCard key={group.area} group={group} index={i} />
          ))}
        </div>
      </section>
    </div>
  );
}

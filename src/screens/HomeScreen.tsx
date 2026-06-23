import { EntityCard } from "../components/EntityCard";
import { CardLink } from "../components/CardLink";
import { SectionHeader } from "../components/SectionHeader";
import { AreaCard } from "../components/AreaCard";
import { overrides } from "../config/overrides";
import { useEntitiesByArea, useEntityStore } from "../store/entityStore";
import { useFavorites } from "../store/prefsStore";

/**
 * Home — the blend's landing screen: a pinned "Home" favorites section
 * (comfortable A-style cards) above the area hub (C-style cards).
 */
export function HomeScreen() {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntitiesByArea();
  const favorites = useFavorites();
  const pinned = favorites.map((id) => entities[id]).filter(Boolean);

  return (
    <div className="space-y-xl">
      {pinned.length > 0 && (
        <section className="space-y-md">
          <SectionHeader label="Home" channel="effort" />
          <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
            {pinned.map((entity) => (
              <CardLink
                key={entity.entity_id}
                to={`/entity/${encodeURIComponent(entity.entity_id)}`}
              >
                <EntityCard
                  entity={entity}
                  overrides={overrides}
                  density="comfortable"
                />
              </CardLink>
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

import { EntityCard } from "../components/EntityCard";
import { SectionHeader } from "../components/SectionHeader";
import { overrides } from "../config/overrides";
import { entities, getEntity, securitySceneIds } from "../fixtures/entities";

/**
 * Option B — "Dense grid".
 * Compact tiles, more visible at once, closer to a control panel. Good for power
 * users on desktop. The Security scene renders as a tight grid up top.
 */
export function OptionB() {
  const sceneSet = new Set<string>(securitySceneIds);
  const rest = entities.filter((e) => !sceneSet.has(e.entity_id));

  return (
    <div className="space-y-xl">
      <section className="space-y-md">
        <SectionHeader label="Security" channel="recovery" />
        <div className="grid grid-cols-2 gap-sm lg:grid-cols-4">
          {securitySceneIds.map((id) => (
            <EntityCard
              key={id}
              entity={getEntity(id)}
              overrides={overrides}
              density="compact"
            />
          ))}
        </div>
      </section>

      <section className="space-y-md">
        <SectionHeader label="Everything else" channel="effort" />
        <div className="grid grid-cols-2 gap-sm lg:grid-cols-4">
          {rest.map((entity) => (
            <EntityCard
              key={entity.entity_id}
              entity={entity}
              overrides={overrides}
              density="compact"
            />
          ))}
        </div>
      </section>
    </div>
  );
}

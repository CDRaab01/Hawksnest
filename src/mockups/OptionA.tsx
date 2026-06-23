import { EntityCard } from "../components/EntityCard";
import { SectionHeader } from "../components/SectionHeader";
import { overrides } from "../config/overrides";
import { getEntity, securityScene } from "../fixtures/entities";

/**
 * Option A — "Spotter-faithful list".
 * Vertical sections, large comfortable cards, one primary action each. Closest
 * to Spotter's feel. The Security scene leads.
 */
export function OptionA() {
  const [camera, lock, contact, intrusion] = securityScene;

  return (
    <div className="space-y-xl">
      <section className="space-y-md">
        <SectionHeader label="Security" channel="recovery" />
        <EntityCard entity={camera} overrides={overrides} />
        <EntityCard entity={lock} overrides={overrides} />
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
          <EntityCard entity={contact} overrides={overrides} />
          <EntityCard entity={intrusion} overrides={overrides} />
        </div>
      </section>

      <section className="space-y-md">
        <SectionHeader label="Basement" channel="strength" />
        <EntityCard entity={getEntity("light.basement")} overrides={overrides} />
      </section>

      <section className="space-y-md">
        <SectionHeader label="Doors" channel="effort" />
        <EntityCard entity={getEntity("lock.back_door_lock")} overrides={overrides} />
        <EntityCard entity={getEntity("alarm_control_panel.home")} overrides={overrides} />
        <EntityCard entity={getEntity("sensor.front_door_battery")} overrides={overrides} />
      </section>
    </div>
  );
}

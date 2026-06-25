import { PulseButton } from "../PulseButton";
import { callService } from "../../store/connection";
import { domainOf, type HassEntity } from "../../lib/ha";

/**
 * Inline one-tap control for the Devices list, so common actions don't require
 * drilling into the entity screen. Renders nothing for read-only domains. Each
 * control is a real <button>, so inside a CardLink it acts in place.
 */
export function QuickControl({ entity }: { entity: HassEntity }) {
  const domain = domainOf(entity.entity_id);

  if (domain === "lock") {
    const locked = entity.state === "locked";
    return (
      <PulseButton
        variant="ghost"
        compact
        onClick={() =>
          void callService("lock", locked ? "unlock" : "lock", {
            entity_id: entity.entity_id,
          })
        }
      >
        {locked ? "Unlock" : "Lock"}
      </PulseButton>
    );
  }

  if (domain === "light" || domain === "switch" || domain === "fan") {
    const on = entity.state === "on";
    return (
      <PulseButton
        variant="ghost"
        compact
        active={on}
        onClick={() =>
          void callService(domain, on ? "turn_off" : "turn_on", {
            entity_id: entity.entity_id,
          })
        }
      >
        {on ? "On" : "Off"}
      </PulseButton>
    );
  }

  return null;
}

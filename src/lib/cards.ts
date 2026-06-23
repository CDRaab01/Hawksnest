import { domainOf } from "./ha";
import { LockCard } from "../cards/LockCard";
import { CameraTile } from "../cards/CameraTile";
import { BinarySensorCard } from "../cards/BinarySensorCard";
import { LightCard } from "../cards/LightCard";
import { AlarmCard } from "../cards/AlarmCard";
import { GenericCard } from "../cards/GenericCard";
import type { CardComponent } from "../cards/types";

/** Domain → first-class card. Anything unmapped falls back to GenericCard. */
const CARD_BY_DOMAIN: Record<string, CardComponent> = {
  lock: LockCard,
  camera: CameraTile,
  image: CameraTile,
  binary_sensor: BinarySensorCard,
  light: LightCard,
  alarm_control_panel: AlarmCard,
};

/**
 * Resolve the card component for an entity_id. Never throws on an unknown domain
 * — returns the read-only GenericCard so the UI degrades instead of crashing.
 */
export function domainToCard(entityId: string): CardComponent {
  return CARD_BY_DOMAIN[domainOf(entityId)] ?? GenericCard;
}

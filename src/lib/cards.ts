import { domainOf } from "./ha";
import { LockCard } from "../cards/LockCard";
import { CameraTile } from "../cards/CameraTile";
import { BinarySensorCard } from "../cards/BinarySensorCard";
import { LightCard } from "../cards/LightCard";
import { AlarmCard } from "../cards/AlarmCard";
import { CoverCard } from "../cards/CoverCard";
import { ClimateCard } from "../cards/ClimateCard";
import { MediaPlayerCard } from "../cards/MediaPlayerCard";
import { FanCard } from "../cards/FanCard";
import { SwitchCard } from "../cards/SwitchCard";
import { SceneCard } from "../cards/SceneCard";
import { GenericCard } from "../cards/GenericCard";
import type { CardComponent } from "../cards/types";

/**
 * Domain → first-class card. Anything unmapped falls back to GenericCard.
 *
 * Keep this in step with `density.ts`'s `CONTROL_DOMAINS`: a domain listed there gets a
 * comfortable, control-sized card, so if it has no entry here it renders a control-shaped thing
 * that cannot be operated. `switch` and `scene` both sat in exactly that gap — the only way to
 * flip a switch in the whole web app was the Devices list's inline QuickControl, and a scene
 * rendered its last-run ISO timestamp as if that were a reading. `cards.test.ts` now pins the
 * two lists against each other so the next control domain can't repeat it.
 */
const CARD_BY_DOMAIN: Record<string, CardComponent> = {
  lock: LockCard,
  camera: CameraTile,
  image: CameraTile,
  binary_sensor: BinarySensorCard,
  light: LightCard,
  switch: SwitchCard,
  alarm_control_panel: AlarmCard,
  cover: CoverCard,
  climate: ClimateCard,
  media_player: MediaPlayerCard,
  fan: FanCard,
  scene: SceneCard,
};

/**
 * Resolve the card component for an entity_id. Never throws on an unknown domain
 * — returns the read-only GenericCard so the UI degrades instead of crashing.
 */
export function domainToCard(entityId: string): CardComponent {
  return CARD_BY_DOMAIN[domainOf(entityId)] ?? GenericCard;
}

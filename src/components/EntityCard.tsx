import { domainToCard } from "../lib/cards";
import type { HassEntity } from "../lib/ha";
import type { OverrideMap } from "../lib/resolve";
import type { Density } from "../cards/types";

interface EntityCardProps {
  entity: HassEntity;
  overrides: OverrideMap;
  density?: Density;
}

/** Picks the right domain card for an entity and renders it. */
export function EntityCard({ entity, overrides, density }: EntityCardProps) {
  const Card = domainToCard(entity.entity_id);
  return <Card entity={entity} overrides={overrides} density={density} />;
}

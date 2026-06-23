import type { AreaRegistry, HassEntity } from "./ha";

export interface AreaGroup {
  area: string;
  entities: HassEntity[];
}

const DEFAULT_ORDER = ["Front Door", "Back Door", "Basement", "Security"];

/**
 * Group entities by their assigned area (from the area registry). Entities with
 * no area land in "Unassigned". A preferred ordering floats known areas to the
 * top; the rest follow alphabetically.
 *
 * `hidden` (Phase 3 personalization) drops entities the user hid, so area
 * counts, previews, and detail all agree.
 */
export function groupByArea(
  entities: HassEntity[],
  areas: AreaRegistry,
  order: string[] = DEFAULT_ORDER,
  hidden: string[] = [],
): AreaGroup[] {
  const hiddenSet = new Set(hidden);
  const groups = new Map<string, HassEntity[]>();
  for (const entity of entities) {
    if (hiddenSet.has(entity.entity_id)) continue;
    const area = areas[entity.entity_id] ?? "Unassigned";
    const list = groups.get(area) ?? [];
    list.push(entity);
    groups.set(area, list);
  }
  return [...groups.keys()]
    .sort((a, b) => {
      const ai = order.indexOf(a);
      const bi = order.indexOf(b);
      if (ai !== -1 || bi !== -1) {
        return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi);
      }
      return a.localeCompare(b);
    })
    .map((area) => ({ area, entities: groups.get(area)! }));
}

import type { HassEntities } from "home-assistant-js-websocket";
import type { AreaRegistry, HassEntity } from "../../lib/ha";

// Minimal shapes of HA's registry list responses (config/*_registry/list).
export interface AreaRegistryEntry {
  area_id: string;
  name: string;
}
export interface EntityRegistryEntry {
  entity_id: string;
  area_id: string | null;
  device_id: string | null;
}
export interface DeviceRegistryEntry {
  id: string;
  area_id: string | null;
}

/**
 * Narrow the library's HassEntities (which carry context/last_changed/etc.) to
 * the minimal HassEntity shape the store and cards use.
 */
export function toEntityRecord(
  entities: HassEntities,
): Record<string, HassEntity> {
  const out: Record<string, HassEntity> = {};
  for (const id in entities) {
    const e = entities[id];
    out[id] = { entity_id: e.entity_id, state: e.state, attributes: e.attributes };
  }
  return out;
}

/**
 * Resolve each entity's area name from the three registries. An entity's area
 * comes from its own `area_id`, falling back to its device's `area_id`. Entities
 * with no resolvable area are omitted (they land in "Unassigned" at group time).
 */
export function buildAreaRegistry(
  areas: AreaRegistryEntry[],
  entities: EntityRegistryEntry[],
  devices: DeviceRegistryEntry[],
): AreaRegistry {
  const nameByArea = new Map(areas.map((a) => [a.area_id, a.name]));
  const areaByDevice = new Map(devices.map((d) => [d.id, d.area_id]));

  const out: AreaRegistry = {};
  for (const entity of entities) {
    const areaId =
      entity.area_id ??
      (entity.device_id ? (areaByDevice.get(entity.device_id) ?? null) : null);
    if (!areaId) continue;
    const name = nameByArea.get(areaId);
    if (name) out[entity.entity_id] = name;
  }
  return out;
}

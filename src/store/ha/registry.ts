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
  /** HA's category for non-primary entities — "config" | "diagnostic" | null. */
  entity_category?: "config" | "diagnostic" | null;
  /** The integration that owns the entity (e.g. "zwave_js", "ring"). */
  platform?: string | null;
}

/** HA's integration platform id for Z-Wave JS entities. */
export const ZWAVE_PLATFORM = "zwave_js";

/**
 * The entity ids owned by the Z-Wave JS integration (`platform === "zwave_js"`).
 * Used to detect a controller/radio outage: if every Z-Wave entity reports
 * `unavailable` at once, the stick or zwave-js-ui is down — not one dead node.
 */
/**
 * entity_id → integration platform id ("ring", "mqtt", "zwave_js", …). Powers
 * the ring/ring-mqtt dedupe (src/lib/dedupe.ts).
 */
export function buildEntityPlatforms(
  entities: EntityRegistryEntry[],
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const e of entities) {
    if (e.entity_id && e.platform) out[e.entity_id] = e.platform;
  }
  return out;
}

export function buildZWaveEntityIds(entities: EntityRegistryEntry[]): string[] {
  return entities
    .filter((e) => e.platform === ZWAVE_PLATFORM)
    .map((e) => e.entity_id);
}

/** Entity categories the main Devices list + History demote out of view (kept under device detail). */
export const HIDDEN_ENTITY_CATEGORIES = new Set(["config", "diagnostic"]);

/**
 * entity_id → entity_category for entities HA marks `config`/`diagnostic` — the field HA's own app
 * uses to demote battery/last-activity/volume/calibration toggles out of the primary device list.
 * Only the hidden categories are retained.
 */
export function buildEntityCategories(
  entities: EntityRegistryEntry[],
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const e of entities) {
    if (e.entity_category && HIDDEN_ENTITY_CATEGORIES.has(e.entity_category)) {
      out[e.entity_id] = e.entity_category;
    }
  }
  return out;
}
export interface DeviceRegistryEntry {
  id: string;
  area_id: string | null;
  name?: string | null;
  name_by_user?: string | null;
  manufacturer?: string | null;
  model?: string | null;
  sw_version?: string | null;
}

/** A device resolved from the registries, for the Devices hub registry view. */
export interface DeviceRecord {
  id: string;
  /** User-given name wins over the integration's name. */
  name: string;
  area: string | null;
  manufacturer: string | null;
  model: string | null;
  swVersion: string | null;
  /** Entity ids that belong to this device. */
  entityIds: string[];
}

export interface DeviceIndex {
  /** Device id → record. */
  devices: Record<string, DeviceRecord>;
  /** Entity id → owning device id. */
  entityToDevice: Record<string, string>;
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
    out[id] = {
      entity_id: e.entity_id,
      state: e.state,
      attributes: e.attributes,
      last_changed: e.last_changed,
      last_updated: e.last_updated,
    };
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

/**
 * Build the device index the Devices hub uses: a record per device (resolved
 * name, area name, manufacturer/model/firmware, and its entity ids) plus an
 * entity→device lookup. The previous `buildAreaRegistry` discards all of this;
 * we now retain it from the same three registry payloads.
 */
export function buildDeviceIndex(
  areas: AreaRegistryEntry[],
  entities: EntityRegistryEntry[],
  devices: DeviceRegistryEntry[],
): DeviceIndex {
  const nameByArea = new Map(areas.map((a) => [a.area_id, a.name]));
  const entityToDevice: Record<string, string> = {};
  const entityIdsByDevice = new Map<string, string[]>();

  for (const entity of entities) {
    if (!entity.device_id) continue;
    entityToDevice[entity.entity_id] = entity.device_id;
    const list = entityIdsByDevice.get(entity.device_id) ?? [];
    list.push(entity.entity_id);
    entityIdsByDevice.set(entity.device_id, list);
  }

  const out: Record<string, DeviceRecord> = {};
  for (const d of devices) {
    const area = d.area_id ? (nameByArea.get(d.area_id) ?? null) : null;
    out[d.id] = {
      id: d.id,
      name: d.name_by_user?.trim() || d.name?.trim() || "Device",
      area,
      manufacturer: d.manufacturer ?? null,
      model: d.model ?? null,
      swVersion: d.sw_version ?? null,
      entityIds: entityIdsByDevice.get(d.id) ?? [],
    };
  }
  return { devices: out, entityToDevice };
}

import type { RegistryData, HaStateObj, RawHistorySample } from "../wsProtocol";

/** entity_id -> area name, mirroring the app's demo area registry. */
const AREA_BY_ENTITY: Record<string, string> = {
  "camera.front_door": "Front Door",
  "binary_sensor.front_door_ding": "Front Door",
  "lock.front_door_lock": "Front Door",
  "binary_sensor.front_door_current_status": "Front Door",
  "sensor.front_door_battery": "Front Door",
  "lock.back_door_lock": "Back Door",
  "camera.backyard": "Backyard",
  "light.basement": "Basement",
  "alarm_control_panel.home": "Security",
  "camera.living_room": "Living Room",
  "light.living_room": "Living Room",
  "switch.porch": "Front Door",
};

const slug = (name: string): string => name.toLowerCase().replace(/[^a-z0-9]+/g, "_");

/** Z-Wave-owned domains, so the app's Z-Wave outage detection has data to read. */
const ZWAVE_DOMAINS = new Set(["lock", "light", "switch"]);

/**
 * Build the three registry list responses from the entity snapshot. Each entity
 * gets its `area_id` directly (no devices needed — `buildAreaRegistry` resolves
 * entity.area_id first), and locks/lights/switches are tagged `zwave_js`.
 */
export function buildRegistries(entities: HaStateObj[]): RegistryData {
  const areaNames = [...new Set(Object.values(AREA_BY_ENTITY))];
  return {
    areas: areaNames.map((name) => ({ area_id: slug(name), name })),
    entities: entities.map((e) => {
      const area = AREA_BY_ENTITY[e.entity_id];
      const domain = e.entity_id.split(".", 1)[0];
      return {
        entity_id: e.entity_id,
        area_id: area ? slug(area) : null,
        device_id: null,
        entity_category: null,
        platform: ZWAVE_DOMAINS.has(domain) ? "zwave_js" : null,
      };
    }),
    devices: [],
  };
}

/** A flat, plausible history series (compressed form: epoch-second `lu`). */
export function buildHistory(entities: HaStateObj[]): Record<string, RawHistorySample[]> {
  const now = Math.floor(Date.now() / 1000);
  const out: Record<string, RawHistorySample[]> = {};
  for (const e of entities) {
    out[e.entity_id] = [
      { s: e.state, lu: now - 3600 },
      { s: e.state, lu: now - 60 },
    ];
  }
  return out;
}

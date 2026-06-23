import type { AreaRegistry, HassEntity } from "../lib/ha";

/**
 * Invented Phase 0 fixtures — no live HA. Friendly names are intentionally left
 * as the ugly, attribute-derived strings stock HA actually shows (e.g. "Lock
 * Current status"), so the label-resolution layer + overrides have something
 * real to fix. Shapes match `home-assistant-js-websocket` HassEntity.
 */
export const entities: HassEntity[] = [
  {
    entity_id: "camera.front_door",
    state: "streaming",
    attributes: { friendly_name: "Front Door Camera", icon: "mdi:cctv" },
  },
  {
    // Stock HA shows this simply as "Lock" — override forces "Front Door".
    entity_id: "lock.front_door_lock",
    state: "locked",
    attributes: { friendly_name: "Lock" },
  },
  {
    // The poster child: HA exposes the contact as "Lock Current status".
    entity_id: "binary_sensor.front_door_current_status",
    state: "on",
    attributes: { friendly_name: "Lock Current status", device_class: "door" },
  },
  {
    entity_id: "binary_sensor.front_door_intrusion",
    state: "off",
    attributes: { friendly_name: "Lock Intrusion", device_class: "safety" },
  },
  {
    entity_id: "sensor.front_door_battery",
    state: "100",
    attributes: {
      friendly_name: "Front Door Battery",
      device_class: "battery",
      unit_of_measurement: "%",
    },
  },
  {
    entity_id: "lock.back_door_lock",
    state: "locked",
    attributes: { friendly_name: "Lock" },
  },
  {
    entity_id: "light.basement",
    state: "on",
    attributes: { friendly_name: "Basement", brightness: 153 },
  },
  {
    entity_id: "alarm_control_panel.home",
    state: "disarmed",
    attributes: { friendly_name: "Alarm" },
  },
];

/** Area assignment — a registry concern, kept separate from state (see ha.ts). */
export const areaRegistry: AreaRegistry = {
  "camera.front_door": "Front Door",
  "lock.front_door_lock": "Front Door",
  "binary_sensor.front_door_current_status": "Front Door",
  "binary_sensor.front_door_intrusion": "Front Door",
  "sensor.front_door_battery": "Front Door",
  "lock.back_door_lock": "Back Door",
  "light.basement": "Basement",
  "alarm_control_panel.home": "Security",
};

const byId = new Map(entities.map((e) => [e.entity_id, e]));

export function getEntity(entityId: string): HassEntity {
  const entity = byId.get(entityId);
  if (!entity) throw new Error(`Unknown fixture entity: ${entityId}`);
  return entity;
}

/**
 * The exact "Security" scene from the owner's stock-HA screenshot, in order:
 * camera, lock, door contact, intrusion. Every direction renders this.
 */
export const securitySceneIds = [
  "camera.front_door",
  "lock.front_door_lock",
  "binary_sensor.front_door_current_status",
  "binary_sensor.front_door_intrusion",
] as const;

export const securityScene = securitySceneIds.map(getEntity);

/** Group all fixtures by area in a stable order (for the Area-first hub). */
export function entitiesByArea(): { area: string; entities: HassEntity[] }[] {
  const order = ["Front Door", "Back Door", "Basement", "Security"];
  const groups = new Map<string, HassEntity[]>();
  for (const entity of entities) {
    const area = areaRegistry[entity.entity_id] ?? "Unassigned";
    const list = groups.get(area) ?? [];
    list.push(entity);
    groups.set(area, list);
  }
  return [...groups.keys()]
    .sort((a, b) => {
      const ai = order.indexOf(a);
      const bi = order.indexOf(b);
      return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi);
    })
    .map((area) => ({ area, entities: groups.get(area)! }));
}

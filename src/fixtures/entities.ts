import type { AreaRegistry, HassEntity } from "../lib/ha";

/**
 * Invented Phase 0/2 fixtures — no live HA. Friendly names are intentionally
 * left as the ugly, attribute-derived strings stock HA actually shows (e.g.
 * "Lock Current status"), so the label-resolution layer + overrides have
 * something real to fix. Shapes match `home-assistant-js-websocket` HassEntity.
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
  {
    entity_id: "cover.living_room_blinds",
    state: "closed",
    attributes: {
      friendly_name: "Living Room Blinds",
      device_class: "blind",
      current_position: 0,
    },
  },
  {
    entity_id: "climate.living_room",
    state: "heat",
    attributes: {
      friendly_name: "Living Room Thermostat",
      current_temperature: 69,
      temperature: 71,
      target_temp_step: 0.5,
      unit_of_measurement: "°F",
    },
  },
  {
    entity_id: "media_player.living_room",
    state: "playing",
    attributes: {
      friendly_name: "Living Room Speaker",
      media_title: "Nightcall",
      media_artist: "Kavinsky",
    },
  },
  {
    entity_id: "fan.bedroom",
    state: "on",
    attributes: { friendly_name: "Bedroom Fan", percentage: 66 },
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
  "cover.living_room_blinds": "Living Room",
  "climate.living_room": "Living Room",
  "media_player.living_room": "Living Room",
  "fan.bedroom": "Bedroom",
};

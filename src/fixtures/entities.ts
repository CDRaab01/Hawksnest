import type { AreaRegistry, HassEntity } from "../lib/ha";

/** A recent ISO timestamp, computed at load so demo "last seen" reads fresh. */
const ago = (mins: number) => new Date(Date.now() - mins * 60_000).toISOString();

/**
 * Invented Phase 0/2 fixtures — no live HA. Friendly names are intentionally
 * left as the ugly, attribute-derived strings stock HA actually shows (e.g.
 * "Lock Current status"), so the label-resolution layer + overrides have
 * something real to fix. Shapes match `home-assistant-js-websocket` HassEntity.
 *
 * Demo cameras point `entity_picture` at bundled stylized SVG "feeds" so the
 * camera wall renders real <img> tiles without a live HA backend.
 */
export const entities: HassEntity[] = [
  {
    entity_id: "camera.front_door",
    state: "streaming",
    attributes: {
      friendly_name: "Front Door Camera",
      icon: "mdi:cctv",
      entity_picture: "/demo-cam-3.svg",
    },
    last_changed: ago(1),
  },
  {
    entity_id: "camera.backyard",
    state: "streaming",
    attributes: {
      friendly_name: "Backyard Camera",
      icon: "mdi:cctv",
      entity_picture: "/demo-cam-1.svg",
    },
    last_changed: ago(2),
  },
  {
    entity_id: "camera.living_room",
    state: "streaming",
    attributes: {
      friendly_name: "Living Room Camera",
      icon: "mdi:cctv",
      entity_picture: "/demo-cam-2.svg",
    },
    last_changed: ago(1),
  },
  {
    entity_id: "camera.basement",
    state: "idle",
    attributes: {
      friendly_name: "Basement Camera",
      icon: "mdi:cctv",
      entity_picture: "/demo-cam-2.svg",
    },
    last_changed: ago(4),
  },
  {
    // Doorbell ring sensor for the front-door camera (ring-mqtt names it
    // `binary_sensor.<base>_ding`). Idle in demo; flipping it on raises the
    // app-wide doorbell banner. Resolved onto the camera by base name.
    entity_id: "binary_sensor.front_door_ding",
    state: "off",
    attributes: { friendly_name: "Front Door Ding", device_class: "occupancy" },
    last_changed: ago(30),
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
  {
    entity_id: "binary_sensor.backyard_motion",
    state: "off",
    attributes: { friendly_name: "Backyard Motion", device_class: "motion" },
    last_changed: ago(9),
  },
  {
    // Low battery — surfaces in the Devices "Needs attention" rail.
    entity_id: "sensor.garage_door_battery",
    state: "14",
    attributes: {
      friendly_name: "Garage Door Battery",
      device_class: "battery",
      unit_of_measurement: "%",
    },
    last_changed: ago(40),
  },
  {
    // Offline — also "Needs attention".
    entity_id: "light.garage",
    state: "unavailable",
    attributes: { friendly_name: "Garage Light" },
    last_changed: ago(180),
  },
  // People + the sun: not "devices" (filtered from the Devices hub), but they
  // populate the automation builder's presence and sunrise/sunset pickers.
  {
    entity_id: "person.alex",
    state: "home",
    attributes: { friendly_name: "Alex" },
    last_changed: ago(95),
  },
  {
    entity_id: "person.sam",
    state: "not_home",
    attributes: { friendly_name: "Sam" },
    last_changed: ago(220),
  },
  {
    entity_id: "sun.sun",
    state: "above_horizon",
    attributes: {
      friendly_name: "Sun",
      next_rising: ago(-540),
      next_setting: ago(-180),
    },
    last_changed: ago(300),
  },
];

/** Area assignment — a registry concern, kept separate from state (see ha.ts). */
export const areaRegistry: AreaRegistry = {
  "camera.front_door": "Front Door",
  "binary_sensor.front_door_ding": "Front Door",
  "lock.front_door_lock": "Front Door",
  "binary_sensor.front_door_current_status": "Front Door",
  "binary_sensor.front_door_intrusion": "Front Door",
  "sensor.front_door_battery": "Front Door",
  "lock.back_door_lock": "Back Door",
  "camera.backyard": "Backyard",
  "binary_sensor.backyard_motion": "Backyard",
  "camera.basement": "Basement",
  "light.basement": "Basement",
  "alarm_control_panel.home": "Security",
  "camera.living_room": "Living Room",
  "cover.living_room_blinds": "Living Room",
  "climate.living_room": "Living Room",
  "media_player.living_room": "Living Room",
  "fan.bedroom": "Bedroom",
  "sensor.garage_door_battery": "Garage",
  "light.garage": "Garage",
};

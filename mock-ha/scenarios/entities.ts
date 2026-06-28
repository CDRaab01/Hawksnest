import type { HaStateObj } from "../wsProtocol";

/** A recent ISO timestamp, computed at load so "last seen" reads fresh. */
const ago = (mins: number): string => new Date(Date.now() - mins * 60_000).toISOString();

/**
 * Initial entity snapshot for the mock. Entity ids mirror the app's demo fixtures
 * (`src/fixtures/entities.ts`) so the same UI assertions work against the live
 * `haSource` path — plus `light.living_room` and `switch.porch` for the
 * state-echo / service round-trip specs. Friendly names are intentionally left as
 * the ugly attribute-derived strings stock HA shows, so the label-resolution
 * layer has something real to fix.
 */
export const baseEntities: HaStateObj[] = [
  {
    entity_id: "camera.front_door",
    state: "streaming",
    attributes: { friendly_name: "Front Door Camera", icon: "mdi:cctv", entity_picture: "/demo-cam-3.svg" },
    last_changed: ago(1),
  },
  {
    entity_id: "camera.backyard",
    state: "streaming",
    attributes: { friendly_name: "Backyard Camera", icon: "mdi:cctv", entity_picture: "/demo-cam-1.svg" },
    last_changed: ago(2),
  },
  {
    entity_id: "camera.living_room",
    state: "streaming",
    attributes: { friendly_name: "Living Room Camera", icon: "mdi:cctv", entity_picture: "/demo-cam-2.svg" },
    last_changed: ago(1),
  },
  {
    // Doorbell ring sensor — flipping it `on` raises the app-wide doorbell banner.
    entity_id: "binary_sensor.front_door_ding",
    state: "off",
    attributes: { friendly_name: "Front Door Ding", device_class: "occupancy" },
    last_changed: ago(30),
  },
  {
    entity_id: "lock.front_door_lock",
    state: "locked",
    attributes: { friendly_name: "Lock" },
  },
  {
    entity_id: "lock.back_door_lock",
    state: "locked",
    attributes: { friendly_name: "Lock" },
  },
  {
    entity_id: "binary_sensor.front_door_current_status",
    state: "on",
    attributes: { friendly_name: "Lock Current status", device_class: "door" },
  },
  {
    entity_id: "sensor.front_door_battery",
    state: "100",
    attributes: { friendly_name: "Front Door Battery", device_class: "battery", unit_of_measurement: "%" },
  },
  {
    entity_id: "light.living_room",
    state: "off",
    attributes: { friendly_name: "Living Room Light" },
  },
  {
    entity_id: "light.basement",
    state: "on",
    attributes: { friendly_name: "Basement", brightness: 153 },
  },
  {
    entity_id: "switch.porch",
    state: "off",
    attributes: { friendly_name: "Porch" },
  },
  {
    entity_id: "alarm_control_panel.home",
    state: "disarmed",
    attributes: { friendly_name: "Alarm" },
  },
];

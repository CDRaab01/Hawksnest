import { describe, it, expect } from "vitest";
import { dedupeRingMqtt, RING_PLATFORM, MQTT_PLATFORM } from "../dedupe";
import type { HassEntity } from "../ha";

const light = (id: string, name: string): HassEntity => ({
  entity_id: id,
  state: "off",
  attributes: { friendly_name: name },
});

describe("dedupeRingMqtt", () => {
  it("drops the ring twin of an mqtt light — the mqtt one survives", () => {
    const entities = {
      "light.front_light": light("light.front_light", "Front Light"),
      "light.front_light_2": light("light.front_light_2", "Front Light"),
    };
    const platforms = {
      "light.front_light": MQTT_PLATFORM,
      "light.front_light_2": RING_PLATFORM,
    };
    expect(Object.keys(dedupeRingMqtt(entities, platforms))).toEqual([
      "light.front_light",
    ]);
  });

  it("keeps a ring entity with no mqtt twin", () => {
    const entities = { "light.porch": light("light.porch", "Porch") };
    expect(
      Object.keys(dedupeRingMqtt(entities, { "light.porch": RING_PLATFORM })),
    ).toEqual(["light.porch"]);
  });

  it("never collides across different names, domains, or non-ring platforms", () => {
    const entities = {
      "light.front": light("light.front", "Front Light"),
      "light.back": light("light.back", "Back Light"),
      "light.hall": light("light.hall", "Hall"),
      "light.hall_z": light("light.hall_z", "Hall"),
    };
    const platforms = {
      "light.front": MQTT_PLATFORM,
      "light.back": RING_PLATFORM,
      "light.hall": MQTT_PLATFORM,
      "light.hall_z": "zwave_js",
    };
    expect(Object.keys(dedupeRingMqtt(entities, platforms)).sort()).toEqual(
      Object.keys(entities).sort(),
    );
  });

  it("passes everything through before the registry loads (empty platforms)", () => {
    const entities = { "light.front": light("light.front", "Front Light") };
    expect(dedupeRingMqtt(entities, {})).toEqual(entities);
  });
});

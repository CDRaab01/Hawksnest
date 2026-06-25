import { describe, it, expect } from "vitest";
import { entityHealth, summarizeHealth } from "../deviceHealth";
import type { HassEntity } from "../ha";

const e = (over: Partial<HassEntity> & { entity_id: string }): HassEntity => ({
  state: "on",
  attributes: {},
  ...over,
});

describe("entityHealth", () => {
  it("marks unavailable/unknown entities offline", () => {
    expect(entityHealth(e({ entity_id: "light.a", state: "unavailable" })).online).toBe(false);
    expect(entityHealth(e({ entity_id: "light.b", state: "unknown" })).online).toBe(false);
    expect(entityHealth(e({ entity_id: "light.c", state: "on" })).online).toBe(true);
  });

  it("reads a battery percent from a battery-class sensor", () => {
    const h = entityHealth(
      e({
        entity_id: "sensor.lock_battery",
        state: "12",
        attributes: { device_class: "battery" },
      }),
    );
    expect(h.battery).toBe(12);
    expect(h.lowBattery).toBe(true);
    expect(h.needsAttention).toBe(true);
  });

  it("reads battery_level from any entity attribute", () => {
    const h = entityHealth(
      e({ entity_id: "lock.front", attributes: { battery_level: 80 } }),
    );
    expect(h.battery).toBe(80);
    expect(h.lowBattery).toBe(false);
  });

  it("parses last_changed into epoch ms", () => {
    const h = entityHealth(
      e({ entity_id: "light.a", last_changed: "2023-11-14T22:13:20.000Z" }),
    );
    expect(h.lastChangedMs).toBe(Date.parse("2023-11-14T22:13:20.000Z"));
  });
});

describe("summarizeHealth", () => {
  it("counts online / offline / low-battery across a set", () => {
    const summary = summarizeHealth([
      e({ entity_id: "light.a", state: "on" }),
      e({ entity_id: "light.b", state: "unavailable" }),
      e({
        entity_id: "sensor.c",
        state: "5",
        attributes: { device_class: "battery" },
      }),
    ]);
    expect(summary).toEqual({ total: 3, online: 2, offline: 1, lowBattery: 1 });
  });
});

import { describe, it, expect } from "vitest";
import {
  entityHealth,
  summarizeHealth,
  zwaveHealth,
  isZWaveDiagnostic,
} from "../deviceHealth";
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

describe("zwaveHealth", () => {
  it("returns all-null for a device with no Z-Wave diagnostics", () => {
    expect(zwaveHealth([e({ entity_id: "sensor.front_door_battery", state: "80" })])).toEqual({
      nodeStatus: null,
      dead: false,
      lastSeenMs: null,
      rttMs: null,
    });
  });

  it("reads node status, last-seen, and round-trip time from siblings", () => {
    const h = zwaveHealth([
      e({ entity_id: "sensor.front_door_node_status", state: "alive" }),
      e({ entity_id: "sensor.front_door_last_seen", state: "2023-11-14T22:13:20.000Z" }),
      e({ entity_id: "sensor.front_door_round_trip_time", state: "42" }),
    ]);
    expect(h.nodeStatus).toBe("alive");
    expect(h.dead).toBe(false);
    expect(h.lastSeenMs).toBe(Date.parse("2023-11-14T22:13:20.000Z"));
    expect(h.rttMs).toBe(42);
  });

  it("flags a dead node", () => {
    const h = zwaveHealth([e({ entity_id: "sensor.back_door_node_status", state: "Dead" })]);
    expect(h.nodeStatus).toBe("dead");
    expect(h.dead).toBe(true);
  });

  it("ignores an unavailable node-status sensor", () => {
    const h = zwaveHealth([e({ entity_id: "sensor.x_node_status", state: "unavailable" })]);
    expect(h.nodeStatus).toBeNull();
  });

  it("identifies the diagnostic entity ids it consumes", () => {
    expect(isZWaveDiagnostic("sensor.front_door_node_status")).toBe(true);
    expect(isZWaveDiagnostic("sensor.front_door_last_seen")).toBe(true);
    expect(isZWaveDiagnostic("sensor.front_door_round_trip_time")).toBe(true);
    expect(isZWaveDiagnostic("sensor.front_door_battery")).toBe(false);
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

import { describe, it, expect } from "vitest";
import {
  isPrimaryEntity,
  isNoiseEntity,
  primaryEntities,
} from "../entityVisibility";
import type { HassEntity } from "../ha";

const ent = (id: string): HassEntity => ({ entity_id: id, state: "on", attributes: {} });

describe("isPrimaryEntity", () => {
  const categories = {
    "sensor.back_battery": "diagnostic",
    "number.back_volume": "config",
  };

  it("keeps real controls and signals", () => {
    for (const id of [
      "lock.front_door",
      "light.basement",
      "camera.back",
      "binary_sensor.back_motion",
      "switch.back_siren",
      "alarm_control_panel.mfa_alarm",
    ]) {
      expect(isPrimaryEntity(id, categories)).toBe(true);
    }
  });

  it("drops HA config/diagnostic entities", () => {
    expect(isPrimaryEntity("sensor.back_battery", categories)).toBe(false);
    expect(isPrimaryEntity("number.back_volume", categories)).toBe(false);
  });

  it("drops untagged ring-mqtt housekeeping entities by suffix", () => {
    for (const id of [
      "sensor.back_last_activity",
      "sensor.back_info",
      "camera.back_event_stream",
      "camera.back_live_stream",
      "select.back_event_select",
      "select.back_bypass_mode",
      "select.back_chirp_tone",
      "camera.back_snapshot",
      "camera.back_live_view",
    ]) {
      expect(isNoiseEntity(id)).toBe(true);
      expect(isPrimaryEntity(id, {})).toBe(false);
    }
  });

  it("does not treat a real control as noise", () => {
    expect(isNoiseEntity("lock.front_door")).toBe(false);
    expect(isNoiseEntity("binary_sensor.back_motion")).toBe(false);
  });

  it("primaryEntities filters a list", () => {
    const list = [
      ent("lock.front_door"),
      ent("sensor.back_last_activity"),
      ent("sensor.back_battery"),
      ent("light.basement"),
    ];
    expect(primaryEntities(list, categories).map((e) => e.entity_id)).toEqual([
      "lock.front_door",
      "light.basement",
    ]);
  });
});

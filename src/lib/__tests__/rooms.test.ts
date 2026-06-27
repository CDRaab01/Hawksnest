import { describe, it, expect } from "vitest";
import type { HassEntity } from "../ha";
import { roomHighlights, roomIconKey } from "../rooms";

function make(
  entity_id: string,
  state: string,
  device_class?: string,
): HassEntity {
  return {
    entity_id,
    state,
    attributes: device_class ? { device_class } : {},
  };
}

describe("roomIconKey", () => {
  it("maps common room names", () => {
    expect(roomIconKey("Kitchen")).toBe("kitchen");
    expect(roomIconKey("Bedroom 2")).toBe("bedroom");
    expect(roomIconKey("Front Room")).toBe("living");
    expect(roomIconKey("Backyard")).toBe("outdoor");
    expect(roomIconKey("Front Door")).toBe("frontdoor");
    expect(roomIconKey("Office")).toBe("office");
    expect(roomIconKey("Unassigned")).toBe("unassigned");
    expect(roomIconKey("Hallway Nook")).toBe("default");
  });
});

describe("roomHighlights", () => {
  it("surfaces unlocked, motion, lights, cameras (priority order, capped at 4)", () => {
    const highlights = roomHighlights([
      make("lock.front", "unlocked"),
      make("binary_sensor.motion", "on", "motion"),
      make("light.a", "on"),
      make("light.b", "off"),
      make("camera.kitchen_live", "streaming"),
      make("camera.kitchen_snapshot", "idle"),
      make("sensor.temp", "71.6", "temperature"),
    ]);
    expect(highlights.map((h) => h.stat)).toEqual([
      "unlocked",
      "motion",
      "lights",
      "cameras",
    ]);
    expect(highlights[0].label).toBe("1 unlocked");
    expect(highlights[2].label).toBe("1 on");
    expect(highlights[2].channel).toBe("strength");
    expect(highlights).toHaveLength(4);
  });

  it("includes a rounded temperature when the room is quieter", () => {
    const highlights = roomHighlights([
      make("light.a", "on"),
      make("sensor.temp", "68", "temperature"),
    ]);
    expect(highlights.map((h) => h.stat)).toEqual(["lights", "temperature"]);
    expect(highlights[1].label).toBe("68°");
  });

  it("is empty when nothing is notable", () => {
    const highlights = roomHighlights([
      make("lock.front", "locked"),
      make("binary_sensor.motion", "off", "motion"),
      make("switch.fan", "off"),
    ]);
    expect(highlights).toHaveLength(0);
  });
});

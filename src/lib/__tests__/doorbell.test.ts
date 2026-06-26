import { describe, it, expect } from "vitest";
import { activeDoorbellPress } from "../doorbell";
import type { LogicalCamera } from "../cameraModel";
import type { HassEntity } from "../ha";

const cam = (id: string, name: string, dingId: string | null): LogicalCamera => ({
  id,
  name,
  liveEntity: { entity_id: `${id}_live`, state: "idle", attributes: {} },
  snapshotEntity: { entity_id: `${id}_snapshot`, state: "idle", attributes: {} },
  eventStreamId: null,
  eventSelectId: null,
  dingId,
  motionId: null,
});

const ding = (id: string, state: string, whenMs: number): HassEntity => ({
  entity_id: id,
  state,
  attributes: {},
  last_changed: new Date(whenMs).toISOString(),
});

const NOW = 1_700_000_000_000;

describe("activeDoorbellPress", () => {
  it("returns a press when a ding sensor is on within the window", () => {
    const cameras = [cam("camera.front", "Front Door", "binary_sensor.front_ding")];
    const entities = { "binary_sensor.front_ding": ding("binary_sensor.front_ding", "on", NOW - 5_000) };
    expect(activeDoorbellPress(cameras, entities, NOW)).toEqual({
      cameraId: "camera.front",
      name: "Front Door",
      whenMs: NOW - 5_000,
    });
  });

  it("ignores an off sensor, a stale press, and cameras without a ding", () => {
    const cameras = [
      cam("camera.front", "Front", "binary_sensor.front_ding"),
      cam("camera.yard", "Yard", null),
    ];
    expect(
      activeDoorbellPress(
        cameras,
        { "binary_sensor.front_ding": ding("binary_sensor.front_ding", "off", NOW) },
        NOW,
      ),
    ).toBeNull();
    expect(
      activeDoorbellPress(
        cameras,
        { "binary_sensor.front_ding": ding("binary_sensor.front_ding", "on", NOW - 60_000) },
        NOW,
        30_000,
      ),
    ).toBeNull();
  });

  it("picks the most recent press across cameras", () => {
    const cameras = [
      cam("camera.a", "A", "binary_sensor.a_ding"),
      cam("camera.b", "B", "binary_sensor.b_ding"),
    ];
    const entities = {
      "binary_sensor.a_ding": ding("binary_sensor.a_ding", "on", NOW - 10_000),
      "binary_sensor.b_ding": ding("binary_sensor.b_ding", "on", NOW - 2_000),
    };
    expect(activeDoorbellPress(cameras, entities, NOW)?.cameraId).toBe("camera.b");
  });
});

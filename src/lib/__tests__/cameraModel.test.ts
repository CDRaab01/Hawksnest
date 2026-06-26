import { describe, it, expect } from "vitest";
import { resolveCameras } from "../cameraModel";
import type { HassEntity } from "../ha";

const ent = (id: string, friendly?: string): HassEntity => ({
  entity_id: id,
  state: "idle",
  attributes: friendly ? { friendly_name: friendly } : {},
});

function map(...es: HassEntity[]): Record<string, HassEntity> {
  return Object.fromEntries(es.map((e) => [e.entity_id, e]));
}

describe("resolveCameras", () => {
  it("collapses a ring-mqtt camera's entities into one logical camera", () => {
    const entities = map(
      ent("camera.front_door_live", "Front Door Live"),
      ent("camera.front_door_snapshot", "Front Door Snapshot"),
      ent("camera.front_door_event", "Front Door Event"),
      ent("select.front_door_event_select", "Front Door Event Select"),
      ent("binary_sensor.front_door_ding", "Front Door Ding"),
      ent("binary_sensor.front_door_motion", "Front Door Motion"),
      // Unrelated entities must be ignored.
      ent("light.kitchen", "Kitchen"),
    );
    const cams = resolveCameras(entities, {});
    expect(cams).toHaveLength(1);
    const c = cams[0];
    expect(c.id).toBe("camera.front_door");
    expect(c.name).toBe("Front Door"); // trailing "Live" stripped
    expect(c.liveEntity.entity_id).toBe("camera.front_door_live");
    expect(c.snapshotEntity.entity_id).toBe("camera.front_door_snapshot");
    expect(c.eventStreamId).toBe("camera.front_door_event");
    expect(c.eventSelectId).toBe("select.front_door_event_select");
    expect(c.dingId).toBe("binary_sensor.front_door_ding");
    expect(c.motionId).toBe("binary_sensor.front_door_motion");
  });

  it("maps a plain HA camera to a logical camera with no siblings", () => {
    const cams = resolveCameras(map(ent("camera.driveway", "Driveway")), {});
    expect(cams).toHaveLength(1);
    expect(cams[0]).toMatchObject({
      id: "camera.driveway",
      name: "Driveway",
      eventStreamId: null,
      eventSelectId: null,
      dingId: null,
      motionId: null,
    });
    // live + snapshot both fall back to the single camera entity.
    expect(cams[0].liveEntity.entity_id).toBe("camera.driveway");
    expect(cams[0].snapshotEntity.entity_id).toBe("camera.driveway");
  });

  it("handles a live-only ring camera (no snapshot) and sorts by id", () => {
    const cams = resolveCameras(
      map(
        ent("camera.zzz_live", "Zzz Live"),
        ent("camera.aaa", "Aaa"),
      ),
      {},
    );
    expect(cams.map((c) => c.id)).toEqual(["camera.aaa", "camera.zzz"]);
    // The live-only camera uses its live entity for both feeds.
    const zzz = cams.find((c) => c.id === "camera.zzz")!;
    expect(zzz.snapshotEntity.entity_id).toBe("camera.zzz_live");
  });
});

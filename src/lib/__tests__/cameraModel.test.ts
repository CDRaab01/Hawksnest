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
      ent("switch.front_door_siren", "Front Door Siren"),
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
    expect(c.sirenSwitchId).toBe("switch.front_door_siren");
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
      sirenSwitchId: null,
    });
    // live + snapshot both fall back to the single camera entity.
    expect(cams[0].liveEntity.entity_id).toBe("camera.driveway");
    expect(cams[0].snapshotEntity.entity_id).toBe("camera.driveway");
  });

  it("folds HA Ring's `_live_view` entity into the base camera (no duplicate tile)", () => {
    const entities = map(
      ent("camera.back", "Back"),
      ent("camera.back_live_view", "Back Live view"),
    );
    const cams = resolveCameras(entities, {});
    expect(cams).toHaveLength(1);
    const c = cams[0];
    expect(c.id).toBe("camera.back");
    expect(c.name).toBe("Back"); // "Live view" stripped
    expect(c.liveEntity.entity_id).toBe("camera.back_live_view");
    expect(c.snapshotEntity.entity_id).toBe("camera.back"); // the real still
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

describe("resolveCameras — doorbell press across backends", () => {
  it("binds a Reolink doorbell's `_visitor` sensor as the ding", () => {
    // What the live rig produces for a Reolink doorbell: Frigate owns the camera
    // entity, and the button press comes from the official Reolink integration as
    // `_visitor` (there is no `_ding` — that name is ring-mqtt's).
    const entities = map(
      ent("camera.front_door", "Front Door"),
      ent("binary_sensor.front_door_visitor", "Front Door Visitor"),
      ent("binary_sensor.front_door_motion", "Front Door Motion"),
    );
    const cams = resolveCameras(entities, {});
    expect(cams).toHaveLength(1);
    expect(cams[0].dingId).toBe("binary_sensor.front_door_visitor");
    expect(cams[0].motionId).toBe("binary_sensor.front_door_motion");
  });

  it("prefers `_ding` over `_visitor` when both are reporting", () => {
    const entities = map(
      ent("camera.front_door", "Front Door"),
      ent("binary_sensor.front_door_ding", "Front Door Ding"),
      ent("binary_sensor.front_door_visitor", "Front Door Visitor"),
    );
    expect(resolveCameras(entities, {})[0].dingId).toBe("binary_sensor.front_door_ding");
  });

  it("skips a retired backend's dead `_ding` in favour of a live `_visitor`", () => {
    // The handover case: the Ring doorbell is gone but ring-mqtt's `_ding` entity is
    // still registered on the canonical slug, reporting `unavailable`. Binding it would
    // wire the banner to a dead sensor, so the live Reolink `_visitor` must win.
    const dead: HassEntity = {
      entity_id: "binary_sensor.front_door_ding",
      state: "unavailable",
      attributes: {},
    };
    const entities = map(
      ent("camera.front_door", "Front Door"),
      dead,
      ent("binary_sensor.front_door_visitor", "Front Door Visitor"),
    );
    expect(resolveCameras(entities, {})[0].dingId).toBe("binary_sensor.front_door_visitor");
  });

  it("falls back to declaration order when no candidate is reporting", () => {
    const off = (id: string): HassEntity => ({
      entity_id: id,
      state: "unavailable",
      attributes: {},
    });
    const entities = map(
      ent("camera.front_door", "Front Door"),
      off("binary_sensor.front_door_ding"),
      off("binary_sensor.front_door_visitor"),
    );
    expect(resolveCameras(entities, {})[0].dingId).toBe("binary_sensor.front_door_ding");
  });

  it("leaves dingId null for a camera with neither sensor", () => {
    const entities = map(
      ent("camera.garage", "Garage"),
      ent("binary_sensor.garage_motion", "Garage Motion"),
    );
    expect(resolveCameras(entities, {})[0].dingId).toBeNull();
  });
});

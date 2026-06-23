import { describe, it, expect } from "vitest";
import type { HassEntities } from "home-assistant-js-websocket";
import { buildAreaRegistry, toEntityRecord } from "../registry";

describe("buildAreaRegistry", () => {
  const areas = [
    { area_id: "a1", name: "Front Door" },
    { area_id: "a2", name: "Basement" },
  ];

  it("resolves an entity's own area_id", () => {
    const reg = buildAreaRegistry(
      areas,
      [{ entity_id: "lock.front", area_id: "a1", device_id: null }],
      [],
    );
    expect(reg["lock.front"]).toBe("Front Door");
  });

  it("falls back to the device's area_id", () => {
    const reg = buildAreaRegistry(
      areas,
      [{ entity_id: "sensor.x", area_id: null, device_id: "d1" }],
      [{ id: "d1", area_id: "a2" }],
    );
    expect(reg["sensor.x"]).toBe("Basement");
  });

  it("omits entities with no resolvable area or an unknown area", () => {
    const reg = buildAreaRegistry(
      areas,
      [
        { entity_id: "sensor.none", area_id: null, device_id: null },
        { entity_id: "sensor.bad", area_id: "ghost", device_id: null },
      ],
      [],
    );
    expect(reg["sensor.none"]).toBeUndefined();
    expect(reg["sensor.bad"]).toBeUndefined();
  });
});

describe("toEntityRecord", () => {
  it("narrows HassEntities to {entity_id, state, attributes}", () => {
    const entities = {
      "lock.front": {
        entity_id: "lock.front",
        state: "locked",
        attributes: { friendly_name: "Lock" },
        last_changed: "now",
        context: { id: "1", parent_id: null, user_id: null },
      },
    } as unknown as HassEntities;
    const rec = toEntityRecord(entities);
    expect(rec["lock.front"]).toEqual({
      entity_id: "lock.front",
      state: "locked",
      attributes: { friendly_name: "Lock" },
    });
  });
});

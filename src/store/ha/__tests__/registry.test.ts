import { describe, it, expect } from "vitest";
import type { HassEntities } from "home-assistant-js-websocket";
import {
  buildAreaRegistry,
  buildDeviceIndex,
  buildEntityCategories,
  buildZWaveEntityIds,
  toEntityRecord,
} from "../registry";

describe("buildZWaveEntityIds", () => {
  it("keeps only entities owned by the zwave_js platform", () => {
    const ids = buildZWaveEntityIds([
      { entity_id: "lock.front", area_id: null, device_id: "d1", platform: "zwave_js" },
      { entity_id: "light.basement", area_id: null, device_id: "d2", platform: "zwave_js" },
      { entity_id: "camera.porch", area_id: null, device_id: "d3", platform: "ring" },
      { entity_id: "sensor.weather", area_id: null, device_id: null },
    ]);
    expect(ids).toEqual(["lock.front", "light.basement"]);
  });
});

describe("buildEntityCategories", () => {
  it("keeps only config/diagnostic entities", () => {
    const cats = buildEntityCategories([
      { entity_id: "camera.basement", area_id: null, device_id: "d1" },
      {
        entity_id: "sensor.basement_battery",
        area_id: null,
        device_id: "d1",
        entity_category: "diagnostic",
      },
      {
        entity_id: "number.basement_volume",
        area_id: null,
        device_id: "d1",
        entity_category: "config",
      },
    ]);
    expect(cats).toEqual({
      "sensor.basement_battery": "diagnostic",
      "number.basement_volume": "config",
    });
    expect(cats["camera.basement"]).toBeUndefined();
  });
});

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
  it("narrows HassEntities to the fields we use (incl. last_changed), dropping context", () => {
    const entities = {
      "lock.front": {
        entity_id: "lock.front",
        state: "locked",
        attributes: { friendly_name: "Lock" },
        last_changed: "2023-11-14T22:13:20.000Z",
        last_updated: "2023-11-14T22:13:20.000Z",
        context: { id: "1", parent_id: null, user_id: null },
      },
    } as unknown as HassEntities;
    const rec = toEntityRecord(entities);
    expect(rec["lock.front"]).toEqual({
      entity_id: "lock.front",
      state: "locked",
      attributes: { friendly_name: "Lock" },
      last_changed: "2023-11-14T22:13:20.000Z",
      last_updated: "2023-11-14T22:13:20.000Z",
    });
    expect("context" in rec["lock.front"]).toBe(false);
  });
});

describe("buildDeviceIndex", () => {
  const areas = [{ area_id: "a1", name: "Front Door" }];

  it("resolves device records (name, area, metadata) and entity ownership", () => {
    const index = buildDeviceIndex(
      areas,
      [
        { entity_id: "lock.front", area_id: null, device_id: "d1" },
        { entity_id: "sensor.front_battery", area_id: null, device_id: "d1" },
        { entity_id: "light.loose", area_id: "a1", device_id: null },
      ],
      [
        {
          id: "d1",
          area_id: "a1",
          name: "Front Lock",
          name_by_user: "Front Door Lock",
          manufacturer: "Acme",
          model: "L-1",
          sw_version: "2.0",
        },
      ],
    );

    expect(index.devices["d1"]).toMatchObject({
      name: "Front Door Lock", // name_by_user wins
      area: "Front Door",
      manufacturer: "Acme",
      model: "L-1",
      swVersion: "2.0",
    });
    expect(index.devices["d1"].entityIds).toEqual([
      "lock.front",
      "sensor.front_battery",
    ]);
    expect(index.entityToDevice["lock.front"]).toBe("d1");
    // Entities with no device aren't in the entity→device map.
    expect(index.entityToDevice["light.loose"]).toBeUndefined();
  });
});

/**
 * `toEntityRecord` is where the app's render stability is won or lost.
 *
 * `subscribeEntities` fires on every state change anywhere in HA and preserves identity for
 * entities that did not change. Narrowing them into fresh objects throws that away, and
 * everything downstream compares by reference: `useEntity` is an `Object.is` selector, and
 * `resolveCameras` / `groupByArea` / the Devices chain are all `useMemo([entities])`. The
 * observable symptom was live HLS restarting mid-stream because `camera.liveEntity` re-keyed
 * the player's source effect.
 */
describe("toEntityRecord identity", () => {
  const raw = (state: string, attributes: Record<string, unknown> = {}) =>
    ({
      entity_id: "light.front",
      state,
      attributes,
      last_changed: "2026-08-12T10:00:00Z",
      last_updated: "2026-08-12T10:00:00Z",
      context: { id: "c1", user_id: null, parent_id: null },
    }) as unknown as HassEntities[string];

  it("reuses the previous object when nothing it carries has changed", () => {
    const attributes = { friendly_name: "Front" };
    const first = toEntityRecord({ "light.front": raw("on", attributes) });
    const second = toEntityRecord({ "light.front": raw("on", attributes) }, first);
    expect(second["light.front"]).toBe(first["light.front"]);
  });

  it("makes a new object when the state changes", () => {
    const attributes = { friendly_name: "Front" };
    const first = toEntityRecord({ "light.front": raw("on", attributes) });
    const second = toEntityRecord({ "light.front": raw("off", attributes) }, first);
    expect(second["light.front"]).not.toBe(first["light.front"]);
    expect(second["light.front"].state).toBe("off");
  });

  it("makes a new object when attributes are replaced", () => {
    // Compared by reference, matching how the websocket library hands them back: a fresh
    // attributes object means something in there changed.
    const first = toEntityRecord({ "light.front": raw("on", { brightness: 10 }) });
    const second = toEntityRecord({ "light.front": raw("on", { brightness: 10 }) }, first);
    expect(second["light.front"]).not.toBe(first["light.front"]);
  });

  it("leaves untouched entities alone while one of their neighbours changes", () => {
    const frontAttrs = { friendly_name: "Front" };
    const backAttrs = { friendly_name: "Back" };
    const back = (state: string) =>
      ({ ...raw(state, backAttrs), entity_id: "light.back" }) as unknown as HassEntities[string];

    const first = toEntityRecord({
      "light.front": raw("on", frontAttrs),
      "light.back": back("on"),
    });
    const second = toEntityRecord(
      { "light.front": raw("on", frontAttrs), "light.back": back("off") },
      first,
    );
    // The whole point: one entity moving must not invalidate the other 300.
    expect(second["light.front"]).toBe(first["light.front"]);
    expect(second["light.back"]).not.toBe(first["light.back"]);
  });
});

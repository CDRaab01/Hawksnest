import { describe, it, expect, beforeEach } from "vitest";
import { useEntityStore } from "../entityStore";
import { createFixtureSource } from "../fixtureSource";
import { groupByArea } from "../../lib/areas";

beforeEach(() => {
  useEntityStore.setState({
    entities: {},
    areas: {},
    status: "connecting",
    error: undefined,
  });
});

describe("fixtureSource", () => {
  it("populates entities + areas and flags demo data", () => {
    createFixtureSource().start();
    const s = useEntityStore.getState();
    expect(s.status).toBe("demo");
    expect(s.entities["lock.front_door_lock"].state).toBe("locked");
    expect(s.areas["lock.front_door_lock"]).toBe("Front Door");
    expect(Object.keys(s.entities).length).toBeGreaterThan(5);
  });
});

describe("upsertEntities", () => {
  it("merges a live update without dropping others", () => {
    createFixtureSource().start();
    useEntityStore.getState().upsertEntities([
      { entity_id: "lock.front_door_lock", state: "unlocked", attributes: {} },
    ]);
    const s = useEntityStore.getState();
    expect(s.entities["lock.front_door_lock"].state).toBe("unlocked");
    expect(s.entities["lock.back_door_lock"].state).toBe("locked");
  });
});

describe("fixtureSource.callService", () => {
  it("simulates control actions by mutating the store", async () => {
    const source = createFixtureSource();
    source.start();
    await source.callService!("lock", "unlock", {
      entity_id: "lock.front_door_lock",
    });
    expect(useEntityStore.getState().entities["lock.front_door_lock"].state).toBe(
      "unlocked",
    );

    await source.callService!("light", "turn_on", {
      entity_id: "light.basement",
      brightness_pct: 50,
    });
    const light = useEntityStore.getState().entities["light.basement"];
    expect(light.state).toBe("on");
    expect(light.attributes.brightness).toBe(128);
  });

  it("simulates the Phase 4 domains (cover / climate / media_player / fan)", async () => {
    const source = createFixtureSource();
    source.start();

    await source.callService!("cover", "open_cover", {
      entity_id: "cover.living_room_blinds",
    });
    const cover = useEntityStore.getState().entities["cover.living_room_blinds"];
    expect(cover.state).toBe("open");
    expect(cover.attributes.current_position).toBe(100);

    await source.callService!("climate", "set_temperature", {
      entity_id: "climate.living_room",
      temperature: 73,
    });
    expect(
      useEntityStore.getState().entities["climate.living_room"].attributes
        .temperature,
    ).toBe(73);

    await source.callService!("media_player", "media_play_pause", {
      entity_id: "media_player.living_room",
    });
    expect(
      useEntityStore.getState().entities["media_player.living_room"].state,
    ).toBe("paused");

    await source.callService!("fan", "set_percentage", {
      entity_id: "fan.bedroom",
      percentage: 25,
    });
    const fan = useEntityStore.getState().entities["fan.bedroom"];
    expect(fan.state).toBe("on");
    expect(fan.attributes.percentage).toBe(25);
  });
});

describe("fixtureSource.fetchHistory", () => {
  it("synthesizes a series ending at the entity's current state", async () => {
    const source = createFixtureSource();
    source.start();

    const numeric = await source.fetchHistory!("sensor.front_door_battery", 24);
    expect(numeric.length).toBeGreaterThanOrEqual(2);
    expect(numeric[numeric.length - 1].state).toBe("100"); // latest = current
    expect(numeric[0].t).toBeLessThan(numeric[numeric.length - 1].t); // oldest-first

    const discrete = await source.fetchHistory!("lock.front_door_lock", 6);
    expect(discrete[discrete.length - 1].state).toBe("locked");
  });

  it("returns an empty series for an unknown entity", async () => {
    const source = createFixtureSource();
    source.start();
    expect(await source.fetchHistory!("sensor.nope", 24)).toEqual([]);
  });
});

describe("groupByArea", () => {
  it("groups by area with known areas ordered first", () => {
    createFixtureSource().start();
    const { entities, areas } = useEntityStore.getState();
    const groups = groupByArea(Object.values(entities), areas);
    expect(groups.map((g) => g.area)).toEqual([
      // Known areas float to the top in DEFAULT_ORDER; the rest follow
      // alphabetically. People + the sun have no area → "Unassigned" trails.
      "Front Door",
      "Back Door",
      "Basement",
      "Security",
      "Backyard",
      "Bedroom",
      "Garage",
      "Living Room",
      "Unassigned",
    ]);
    const frontDoor = groups.find((g) => g.area === "Front Door")!;
    expect(frontDoor.entities.length).toBe(6); // incl. the doorbell ding sensor
  });

  it("excludes hidden entities from counts and grouping", () => {
    createFixtureSource().start();
    const { entities, areas } = useEntityStore.getState();
    const groups = groupByArea(Object.values(entities), areas, undefined, [
      "lock.front_door_lock",
    ]);
    const frontDoor = groups.find((g) => g.area === "Front Door")!;
    expect(frontDoor.entities.length).toBe(5); // 6 minus the hidden lock
    expect(
      frontDoor.entities.some((e) => e.entity_id === "lock.front_door_lock"),
    ).toBe(false);
  });
});

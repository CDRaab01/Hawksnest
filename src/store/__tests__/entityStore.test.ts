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
});

describe("groupByArea", () => {
  it("groups by area with known areas ordered first", () => {
    createFixtureSource().start();
    const { entities, areas } = useEntityStore.getState();
    const groups = groupByArea(Object.values(entities), areas);
    expect(groups.map((g) => g.area)).toEqual([
      "Front Door",
      "Back Door",
      "Basement",
      "Security",
    ]);
    const frontDoor = groups.find((g) => g.area === "Front Door")!;
    expect(frontDoor.entities.length).toBe(5);
  });

  it("excludes hidden entities from counts and grouping", () => {
    createFixtureSource().start();
    const { entities, areas } = useEntityStore.getState();
    const groups = groupByArea(Object.values(entities), areas, undefined, [
      "lock.front_door_lock",
    ]);
    const frontDoor = groups.find((g) => g.area === "Front Door")!;
    expect(frontDoor.entities.length).toBe(4);
    expect(
      frontDoor.entities.some((e) => e.entity_id === "lock.front_door_lock"),
    ).toBe(false);
  });
});

import { describe, it, expect } from "vitest";
import { normalizeLogbook } from "../logbook";

describe("normalizeLogbook", () => {
  it("converts epoch-second `when` to ms and sorts newest-first", () => {
    const events = normalizeLogbook([
      { when: 1_700_000_000, name: "A", message: "older", entity_id: "lock.a" },
      { when: 1_700_000_060, name: "B", message: "newer", entity_id: "lock.b" },
    ]);
    expect(events.map((e) => e.name)).toEqual(["B", "A"]);
    expect(events[1].when).toBe(1_700_000_000_000);
  });

  it("derives domain from the entity_id", () => {
    const [evt] = normalizeLogbook([
      { when: 1_700_000_000, entity_id: "binary_sensor.front_door_motion" },
    ]);
    expect(evt.domain).toBe("binary_sensor");
    expect(evt.name).toBe("binary_sensor.front_door_motion");
  });

  it("synthesizes a message from state when none is given", () => {
    const [evt] = normalizeLogbook([
      { when: 1_700_000_000, name: "Lamp", state: "on" },
    ]);
    expect(evt.message).toBe("changed to on");
  });

  it("drops entries with no usable timestamp", () => {
    expect(normalizeLogbook([{ name: "no when" }])).toHaveLength(0);
  });
});

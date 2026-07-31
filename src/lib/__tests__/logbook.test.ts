import { describe, it, expect } from "vitest";
import {
  LOGBOOK_MAX_EVENTS,
  capLogbook,
  normalizeLogbook,
  type LogEvent,
} from "../logbook";

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

describe("capLogbook", () => {
  const ev = (when: number): LogEvent => ({
    when,
    name: "n",
    message: "m",
    entityId: null,
    domain: null,
    state: null,
  });

  it("passes a small feed through untouched and says nothing was dropped", () => {
    const events = [ev(3), ev(2), ev(1)];
    expect(capLogbook(events, 10)).toEqual({ events, truncated: false });
  });

  it("keeps the NEWEST events, not the oldest", () => {
    // normalizeLogbook sorts newest-first, so the cap has to take from the front. Taking the
    // tail would silently show a month-old window and look like history had stopped updating.
    const events = [ev(5), ev(4), ev(3), ev(2), ev(1)];
    const capped = capLogbook(events, 2);
    expect(capped.events.map((e) => e.when)).toEqual([5, 4]);
    expect(capped.truncated).toBe(true);
  });

  it("reports truncation only when something was actually dropped", () => {
    const events = [ev(2), ev(1)];
    expect(capLogbook(events, 2).truncated).toBe(false);
    expect(capLogbook(events, 1).truncated).toBe(true);
  });

  it("survives a zero or negative limit without returning a partial lie", () => {
    expect(capLogbook([ev(1)], 0)).toEqual({ events: [], truncated: true });
    expect(capLogbook([], 0)).toEqual({ events: [], truncated: false });
  });

  it("defaults to a limit that a day of this instance's traffic would exceed", () => {
    // ~98,000 recorder rows/day measured against the live MariaDB — the default has to be far
    // below that or the cap is decorative.
    expect(LOGBOOK_MAX_EVENTS).toBeLessThan(5000);
    expect(LOGBOOK_MAX_EVENTS).toBeGreaterThan(0);
  });
});

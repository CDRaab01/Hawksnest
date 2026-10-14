import { describe, it, expect } from "vitest";
import {
  ringEventOptions,
  ringEventsFromOptions,
  ringEventsFromSelect,
  ringOptionTimeMs,
} from "../ringEvents";
import type { HassEntity } from "../ha";

const select = (options: unknown, state = "Motion 1"): HassEntity => ({
  entity_id: "select.front_event_select",
  state,
  attributes: { options },
});

const iso = (h: number, m: number) => `2026-09-23T${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:00.000Z`;

describe("ringOptionTimeMs", () => {
  // The bug this function exists to kill: `Date.parse("1")` — which is what "Motion 1" reduces
  // to once a leading word is stripped — is NOT NaN in V8, it resolves to 2001-01-01. The old
  // heuristic therefore timed every ring option, wrongly, by about two decades.
  it("refuses a bare ordinal rather than resolving it to 2001", () => {
    expect(Date.parse("1")).not.toBeNaN(); // the trap, pinned
    expect(ringOptionTimeMs("Motion 1")).toBeNull();
    expect(ringOptionTimeMs("Motion 1 (Transcoded)")).toBeNull();
    expect(ringOptionTimeMs("Ding 5")).toBeNull();
  });

  it("recovers a full ISO instant embedded in the option", () => {
    expect(ringOptionTimeMs(`Motion ${iso(10, 0)}`)).toBe(Date.parse(iso(10, 0)));
    expect(ringOptionTimeMs("2026-09-23T10:00:00+02:00")).toBe(Date.parse("2026-09-23T10:00:00+02:00"));
  });

  // A zone designator is required so both platforms land on the same millisecond.
  it("refuses a zone-less timestamp", () => {
    expect(ringOptionTimeMs("Motion 2026-09-23T10:00:00")).toBeNull();
  });
});

describe("ringEventOptions", () => {
  it("reads the selector's options newest-first", () => {
    expect(ringEventOptions(select(["Motion 1", "Motion 2"]))).toEqual(["Motion 1", "Motion 2"]);
  });

  it("is empty for a missing selector or a non-list options attribute", () => {
    expect(ringEventOptions(undefined)).toEqual([]);
    expect(ringEventOptions(select("nope"))).toEqual([]);
  });

  // HA restores a retired ring-mqtt entity with its last `options` intact, so a dead selector
  // still looks like it holds playable events long after its camera was deleted.
  it("is empty when the selector is registered but not reporting", () => {
    expect(ringEventOptions(select(["Motion 1", "Motion 2"], "unavailable"))).toEqual([]);
    expect(ringEventOptions(select(["Motion 1"], "unknown"))).toEqual([]);
  });
});

describe("ringEventsFromOptions", () => {
  it("pairs options with real decoded times by recency, oldest-first", () => {
    const events = ringEventsFromOptions(
      ["Motion 1", "Motion 2"],
      [20_000, 10_000], // newest-first
      "front",
    );
    expect(events.map((e) => e.startMs)).toEqual([10_000, 20_000]);
    expect(events.map((e) => e.id)).toEqual(["Motion 2", "Motion 1"]);
    expect(events.every((e) => e.hasClip)).toBe(true);
  });

  it("labels ding, motion and anything else", () => {
    const events = ringEventsFromOptions(["Ding 1", "Motion 1", "On-Demand 1"], [3, 2, 1], "front");
    expect(events.map((e) => e.label).sort()).toEqual(["ding", "event", "motion"]);
  });

  // The regression that produced this whole change: `camera.front` inherited 30 frozen options
  // from a retired Ring camera and drew them as a comb of evenly spaced "recordings" — each
  // claiming hasClip — that had never happened. An untimed option is now dropped, not placed.
  it("drops an option whose time cannot be recovered rather than inventing one", () => {
    expect(ringEventsFromOptions(["Motion 1", "Motion 2"], [], "front")).toEqual([]);
  });

  it("keeps the options it can time and drops the rest", () => {
    const events = ringEventsFromOptions(["Motion 1", "Motion 2"], [5_000], "front");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ id: "Motion 1", startMs: 5_000 });
  });
});

describe("ringEventsFromSelect", () => {
  it("times options from the option strings themselves", () => {
    const events = ringEventsFromSelect(select([`Motion ${iso(10, 6)}`, `Ding ${iso(10, 0)}`]), "front");
    expect(events.map((e) => e.startMs)).toEqual([Date.parse(iso(10, 0)), Date.parse(iso(10, 6))]);
  });

  it("is empty for untimed options and for a dead selector", () => {
    expect(ringEventsFromSelect(select(["Motion 1", "Motion 2"]), "front")).toEqual([]);
    expect(ringEventsFromSelect(select([`Motion ${iso(10, 0)}`], "unavailable"), "front")).toEqual([]);
    expect(ringEventsFromSelect(undefined, "front")).toEqual([]);
  });
});

import { describe, it, expect } from "vitest";
import { resolvePtz } from "../cameraPtz";

/**
 * Fixtures mirror the REAL entity set measured on the cluster 2026-07-30 — two
 * E1 Zooms (full controls) and an E1 Pro (pan/tilt only), with the stairway's
 * Reolink device deliberately named `stairway` while its Frigate camera is
 * `camera.first_floor_stairway`.
 */
const DIRS = ["up", "down", "left", "right", "stop"];
const ptzButtons = (slug: string) => DIRS.map((d) => `button.${slug}_ptz_${d}`);

const LIVE = [
  ...ptzButtons("big_room"),
  ...ptzButtons("stairway"),
  ...ptzButtons("kitchen"),
  "number.big_room_zoom",
  "number.big_room_focus",
  "switch.big_room_auto_focus",
  "number.stairway_zoom",
  "number.stairway_focus",
  "switch.stairway_auto_focus",
  // Unrelated noise that must never be mistaken for PTZ.
  "camera.big_room",
  "camera.first_floor_stairway",
  "select.master_bedroom_preset",
  "sensor.kitchen_ptz_pan_position",
];

describe("resolvePtz", () => {
  it("resolves an E1 Zoom's full control set on an exact name match", () => {
    const ptz = resolvePtz("big_room", LIVE);
    expect(ptz).not.toBeNull();
    expect(ptz!.slug).toBe("big_room");
    expect(ptz!.up).toBe("button.big_room_ptz_up");
    expect(ptz!.stop).toBe("button.big_room_ptz_stop");
    expect(ptz!.zoom).toBe("number.big_room_zoom");
    expect(ptz!.focus).toBe("number.big_room_focus");
    expect(ptz!.autofocus).toBe("switch.big_room_auto_focus");
  });

  // The bug this module exists to prevent: entity ids derived from the camera
  // base would find nothing here, and PTZ would vanish with no error.
  it("bridges the stairway alias — Reolink `stairway` vs camera `first_floor_stairway`", () => {
    const ptz = resolvePtz("first_floor_stairway", LIVE);
    expect(ptz?.slug).toBe("stairway");
    expect(ptz?.up).toBe("button.stairway_ptz_up");
    expect(ptz?.zoom).toBe("number.stairway_zoom");
  });

  it("gives the E1 Pro a pad but no zoom/focus/autofocus", () => {
    const ptz = resolvePtz("kitchen", LIVE);
    expect(ptz?.slug).toBe("kitchen");
    expect(ptz?.left).toBe("button.kitchen_ptz_left");
    expect(ptz?.zoom).toBeNull();
    expect(ptz?.focus).toBeNull();
    expect(ptz?.autofocus).toBeNull();
  });

  it("has no preset until one is saved on the camera", () => {
    expect(resolvePtz("big_room", LIVE)?.preset).toBeNull();
    expect(
      resolvePtz("big_room", [...LIVE, "select.big_room_ptz_preset"])?.preset,
    ).toBe("select.big_room_ptz_preset");
  });

  it("returns null for a camera with no PTZ entities at all (Ring)", () => {
    expect(resolvePtz("front_door", LIVE)).toBeNull();
  });

  it("returns null when nothing in the system has PTZ", () => {
    expect(resolvePtz("big_room", ["camera.big_room", "switch.big_room_siren"])).toBeNull();
  });

  // Segment-aware, and trailing-only: a leading fragment is not a match.
  it("does not match a leading fragment of a longer slug", () => {
    expect(resolvePtz("big", LIVE)).toBeNull();
    expect(resolvePtz("first_floor", LIVE)).toBeNull();
  });

  it("does not match a fragment that is not on a segment boundary", () => {
    expect(resolvePtz("oom", LIVE)).toBeNull();
    expect(resolvePtz("way", LIVE)).toBeNull();
  });

  // The documented residual gap of the trailing rule, asserted so it stays a
  // known, pinnable behaviour rather than a surprise. `aliases` is the fix.
  it("does match a bare trailing segment — pin it with an alias if that is wrong", () => {
    expect(resolvePtz("room", LIVE)?.slug).toBe("big_room");
    expect(resolvePtz("room", LIVE, { room: "kitchen" })?.slug).toBe("kitchen");
  });

  // Pointing the pad at the wrong lens is worse than showing no pad, so a base
  // with two valid trailing matches resolves to nothing rather than picking one.
  it("fails closed when two candidates are equally plausible", () => {
    const ambiguous = [...ptzButtons("yard"), ...ptzButtons("side_yard")];
    expect(resolvePtz("north_side_yard", ambiguous)).toBeNull();
    // ...and the alias override is how you break the tie deliberately.
    expect(
      resolvePtz("north_side_yard", ambiguous, { north_side_yard: "side_yard" })?.slug,
    ).toBe("side_yard");
  });

  it("prefers an exact match over a fuzzy one", () => {
    const both = [...ptzButtons("stairway"), ...ptzButtons("first_floor_stairway")];
    expect(resolvePtz("first_floor_stairway", both)?.slug).toBe("first_floor_stairway");
  });

  it("honours an explicit alias override", () => {
    const ptz = resolvePtz("first_floor_stairway", LIVE, {
      first_floor_stairway: "kitchen",
    });
    expect(ptz?.slug).toBe("kitchen");
  });

  it("ignores an override naming a camera that has no PTZ", () => {
    const ptz = resolvePtz("first_floor_stairway", LIVE, {
      first_floor_stairway: "nonexistent",
    });
    // Falls back to the honest segment match rather than resolving to nothing.
    expect(ptz?.slug).toBe("stairway");
  });

  // A pad that can move but not stop would leave the camera panning.
  it("fails closed when the stop button is missing", () => {
    const noStop = LIVE.filter((id) => id !== "button.big_room_ptz_stop");
    expect(resolvePtz("big_room", noStop)).toBeNull();
  });

  it("fails closed when a direction is missing", () => {
    const noLeft = LIVE.filter((id) => id !== "button.big_room_ptz_left");
    expect(resolvePtz("big_room", noLeft)).toBeNull();
  });
});

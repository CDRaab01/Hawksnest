import { describe, it, expect } from "vitest";
import { resolveDoorbellControls } from "../doorbellControls";

/** The real entity set the Reolink integration produced for the D340W doorbell. */
const DOORBELL_SLUG = "front_door_front_door_reolink";
const live = [
  "camera.front_door_reolink",
  "binary_sensor.front_door_reolink_visitor",
  "binary_sensor.front_door_reolink_motion",
  `number.${DOORBELL_SLUG}_doorbell_volume`,
  `number.${DOORBELL_SLUG}_volume`,
  `switch.${DOORBELL_SLUG}_doorbell_button_sound`,
  `select.${DOORBELL_SLUG}_auto_quick_reply_message`,
  `select.${DOORBELL_SLUG}_play_quick_reply_message`,
  `siren.${DOORBELL_SLUG}_siren`,
];

describe("resolveDoorbellControls", () => {
  it("bridges the doubled Reolink slug to the Frigate camera base", () => {
    // The integration names entities `<host device>_<channel device>_<entity>`, so the
    // doorbell's slug is `front_door_front_door_reolink` while its camera is
    // `camera.front_door_reolink`. Deriving ids from the base would find nothing.
    const c = resolveDoorbellControls("front_door_reolink", live);
    expect(c).not.toBeNull();
    expect(c!.slug).toBe(DOORBELL_SLUG);
    expect(c!.volume).toBe(`number.${DOORBELL_SLUG}_doorbell_volume`);
    expect(c!.buttonSound).toBe(`switch.${DOORBELL_SLUG}_doorbell_button_sound`);
    expect(c!.autoReply).toBe(`select.${DOORBELL_SLUG}_auto_quick_reply_message`);
    expect(c!.playReply).toBe(`select.${DOORBELL_SLUG}_play_quick_reply_message`);
  });

  it("resolves the siren from the `siren` domain, not ring-mqtt's switch", () => {
    // `LogicalCamera.sirenSwitchId` only ever looks for `switch.<base>_siren`. The Reolink
    // siren is a different domain with a different service, so it must come from here.
    const c = resolveDoorbellControls("front_door_reolink", live);
    expect(c!.siren).toBe(`siren.${DOORBELL_SLUG}_siren`);
    expect(c!.siren!.startsWith("siren.")).toBe(true);
  });

  it("returns null for a wall camera, so no doorbell chrome appears on it", () => {
    const c = resolveDoorbellControls("kitchen", [
      "camera.kitchen",
      "binary_sensor.kitchen_motion",
      "number.kitchen_volume",
      "siren.kitchen_siren",
    ]);
    expect(c).toBeNull();
  });

  it("prefers an exact slug over a fuzzy one", () => {
    const ids = [
      "number.front_door_reolink_doorbell_volume",
      `number.${DOORBELL_SLUG}_doorbell_volume`,
    ];
    expect(resolveDoorbellControls("front_door_reolink", ids)!.slug).toBe("front_door_reolink");
  });

  it("returns null when two candidates are equally plausible", () => {
    // Guessing would point the volume slider at the wrong doorbell.
    const ids = [
      "number.a_front_door_doorbell_volume",
      "number.b_front_door_doorbell_volume",
    ];
    expect(resolveDoorbellControls("front_door", ids)).toBeNull();
  });

  it("honours an explicit alias", () => {
    const ids = ["number.lobby_unit_doorbell_volume"];
    expect(resolveDoorbellControls("porch", ids, { porch: "lobby_unit" })!.slug).toBe("lobby_unit");
  });

  it("reports optional controls as null rather than inventing ids", () => {
    const c = resolveDoorbellControls("porch", ["number.porch_doorbell_volume"]);
    expect(c).toMatchObject({
      slug: "porch",
      volume: "number.porch_doorbell_volume",
      buttonSound: null,
      autoReply: null,
      playReply: null,
      siren: null,
    });
  });

  it("does not mistake a plain volume entity for a doorbell", () => {
    // Every Reolink has `_volume`; only doorbells have `_doorbell_volume`.
    expect(resolveDoorbellControls("garage", ["number.garage_volume"])).toBeNull();
  });
});

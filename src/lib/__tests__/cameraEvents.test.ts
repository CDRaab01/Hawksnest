import { describe, it, expect } from "vitest";
import {
  normalizeFrigateEvents,
  recordingUrlAt,
  eventClipUrl,
  eventSnapshotUrl,
  FRIGATE_BASE,
  type RawFrigateEvent,
} from "../cameraEvents";

describe("cameraEvents", () => {
  it("normalizes Frigate events: seconds→ms, oldest-first, defensive defaults", () => {
    const raw: RawFrigateEvent[] = [
      { id: "b", camera: "front", label: "person", start_time: 1_700_000_100, end_time: 1_700_000_130, has_clip: true, has_snapshot: true },
      { id: "a", camera: "front", label: "car", start_time: 1_700_000_000, end_time: 1_700_000_050 },
    ];
    const out = normalizeFrigateEvents(raw);

    // Sorted oldest-first (timeline order), times in ms.
    expect(out.map((e) => e.id)).toEqual(["a", "b"]);
    expect(out[0]).toMatchObject({
      id: "a",
      camera: "front",
      label: "car",
      startMs: 1_700_000_000_000,
      endMs: 1_700_000_050_000,
      hasClip: false,
      hasSnapshot: false,
      thumbnailUrl: null,
      snapshotUrl: null,
    });
    expect(out[1].thumbnailUrl).toBe(eventSnapshotUrl("b"));
  });

  it("treats a missing end_time as an ongoing event (endMs null)", () => {
    const [ev] = normalizeFrigateEvents([
      { id: "x", camera: "c", label: "motion", start_time: 1_700_000_000 },
    ]);
    expect(ev.endMs).toBeNull();
  });

  it("drops entries with no id or no usable start time", () => {
    const out = normalizeFrigateEvents([
      { camera: "c", label: "motion", start_time: 1_700_000_000 }, // no id
      { id: "y", camera: "c", label: "motion" }, // no start_time
      { id: "z", camera: "c", label: "motion", start_time: 1_700_000_000 },
    ]);
    expect(out.map((e) => e.id)).toEqual(["z"]);
  });

  it("defaults a missing label to motion", () => {
    const [ev] = normalizeFrigateEvents([
      { id: "n", camera: "c", start_time: 1_700_000_000 },
    ]);
    expect(ev.label).toBe("motion");
  });

  it("builds VOD / clip / snapshot URLs against the default and a custom base", () => {
    expect(recordingUrlAt("front", 1_700_000_000_000, 1_700_000_600_000)).toBe(
      `${FRIGATE_BASE}/vod/front/start/1700000000/end/1700000600/master.m3u8`,
    );
    expect(eventClipUrl("evt-1")).toBe(`${FRIGATE_BASE}/notifications/evt-1/clip.mp4`);
    expect(eventSnapshotUrl("evt-1")).toBe(
      `${FRIGATE_BASE}/notifications/evt-1/snapshot.jpg`,
    );

    const base = "http://ha.local:8123/api/frigate";
    expect(recordingUrlAt("front", 1_700_000_000_000, 1_700_000_600_000, base)).toBe(
      `${base}/vod/front/start/1700000000/end/1700000600/master.m3u8`,
    );
  });
});

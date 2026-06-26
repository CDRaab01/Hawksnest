import { describe, it, expect } from "vitest";
import { createFixtureSource } from "../fixtureSource";
import { DEMO_CLIP_URL, DEMO_POSTER_URL } from "../../lib/cameraEvents";

describe("fixtureSource — camera demo data", () => {
  it("streams the bundled demo clip for camera entities only", async () => {
    const src = createFixtureSource();
    expect(await src.streamUrl!("camera.front_door")).toBe(DEMO_CLIP_URL);
    expect(await src.streamUrl!("light.kitchen")).toBeNull();
  });

  it("points recorded-footage URLs at the demo clip", () => {
    const src = createFixtureSource();
    expect(src.recordingUrlAt!("front_door", 0, 1000)).toBe(DEMO_CLIP_URL);
    expect(src.eventClipUrl!("demo-front_door-3")).toBe(DEMO_CLIP_URL);
  });

  it("synthesizes a populated, in-window, oldest-first 24h event timeline", async () => {
    const src = createFixtureSource();
    const end = 1_700_086_400_000;
    const start = end - 24 * 3600_000;
    const events = await src.fetchCameraEvents!("front_door", start, end);

    expect(events.length).toBeGreaterThan(10); // ~one every 37 min
    // All inside the window, chronological, with demo thumbnails.
    for (const ev of events) {
      expect(ev.startMs).toBeGreaterThanOrEqual(start);
      expect(ev.startMs).toBeLessThanOrEqual(end);
      expect(ev.thumbnailUrl).toBe(DEMO_POSTER_URL);
      expect(ev.camera).toBe("front_door");
      expect(ev.hasClip).toBe(true);
    }
    const times = events.map((e) => e.startMs);
    expect([...times].sort((a, b) => a - b)).toEqual(times);
    // A believable mix of labels (not all identical).
    expect(new Set(events.map((e) => e.label)).size).toBeGreaterThan(1);
  });
});

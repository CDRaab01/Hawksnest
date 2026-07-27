import { describe, it, expect, vi, afterEach } from "vitest";
import {
  fetchRingDevices,
  fetchRingTimeline,
  matchDevice,
  nameSlug,
  toCameraEvent,
  type RingRecording,
} from "../ringTimeline";

const DEVICES = [
  { id: 583050895, name: "Front Door", slug: "front_door" },
  { id: 469488510, name: "Front Driveway", slug: "front_driveway" },
  { id: 515941450, name: "First Floor - Stairway", slug: "first_floor_stairway" },
];

function mockFetch(body: unknown, ok = true) {
  const fn = vi.fn(async () => ({ ok, status: ok ? 200 : 502, json: async () => body }));
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => vi.unstubAllGlobals());

describe("nameSlug", () => {
  it("slugs a Ring device name the way ring-mqtt does", () => {
    expect(nameSlug("First Floor - Stairway")).toBe("first_floor_stairway");
    expect(nameSlug("Back Side Yard")).toBe("back_side_yard");
  });
});

describe("matchDevice", () => {
  // The HA entity ids froze at first discovery and have drifted from the Ring names since
  // (`camera.front_*` is Ring's "Front Driveway"), so the friendly name is the reliable key.
  it("matches on the camera's display name, not its entity id", () => {
    expect(matchDevice(DEVICES, "Front Driveway", "front")?.id).toBe(469488510);
  });

  it("falls back to the entity base when the name doesn't match", () => {
    expect(matchDevice(DEVICES, "Renamed In HA", "front_door")?.id).toBe(583050895);
  });

  it("returns undefined when the camera isn't one of the service's devices", () => {
    expect(matchDevice(DEVICES, "Garage", "garage")).toBeUndefined();
  });
});

describe("toCameraEvent", () => {
  const rec: RingRecording = {
    id: "7667005335319651402",
    startMs: 1785113787075,
    endMs: 1785113844075,
    durationSec: 57,
    kind: "motion",
    person: true,
    url: "https://ring.test/clip.mp4",
    urlExpiresAtMs: 1785115162000,
    thumbnailUrl: "https://ring.test/thumb.jpg",
  };

  it("carries a real span — no learning the duration from the media", () => {
    const event = toCameraEvent(rec, "first_floor_stairway");
    expect(event.startMs).toBe(1785113787075);
    expect(event.endMs).toBe(1785113844075);
    expect(event.hasClip).toBe(true);
  });

  it("labels a person detection as such, and an unknown kind as motion", () => {
    expect(toCameraEvent(rec, "x").label).toBe("person");
    expect(toCameraEvent({ ...rec, person: false }, "x").label).toBe("motion");
    expect(toCameraEvent({ ...rec, person: false, kind: "brand_new" }, "x").label).toBe("motion");
    expect(toCameraEvent({ ...rec, kind: "ding", person: false }, "x").label).toBe("ding");
  });
});

describe("fetchRingTimeline", () => {
  const rec = (over: Partial<RingRecording>): RingRecording => ({
    id: "1",
    startMs: 1000,
    endMs: 2000,
    durationSec: 1,
    kind: "motion",
    person: false,
    url: "https://ring.test/1.mp4",
    urlExpiresAtMs: 9000,
    thumbnailUrl: null,
    ...over,
  });

  it("returns events with their playable URLs and the earliest expiry", async () => {
    mockFetch({
      events: [rec({ id: "a", urlExpiresAtMs: 9000 }), rec({ id: "b", urlExpiresAtMs: 5000 })],
    });
    const t = await fetchRingTimeline(1, "front", 0, 10_000);
    expect(t.events.map((e) => e.id)).toEqual(["a", "b"]);
    expect(t.urls.get("a")).toBe("https://ring.test/1.mp4");
    // The player refreshes against the FIRST URL to die, not the last.
    expect(t.expiresAtMs).toBe(5000);
  });

  it("drops recordings with no URL — every block on the timeline must be watchable", async () => {
    mockFetch({ events: [rec({ id: "a" }), rec({ id: "b", url: null })] });
    const t = await fetchRingTimeline(1, "front", 0, 10_000);
    expect(t.events.map((e) => e.id)).toEqual(["a"]);
  });

  it("surfaces Ring's truncation instead of looking complete", async () => {
    mockFetch({ events: [rec({})], truncated: true });
    expect((await fetchRingTimeline(1, "front", 0, 10_000)).truncated).toBe(true);
  });

  it("rejects when the service is down, so the caller can fall back to the selector", async () => {
    mockFetch({ error: "no" }, false);
    await expect(fetchRingTimeline(1, "front", 0, 10_000)).rejects.toThrow(/HTTP 502/);
  });
});

describe("fetchRingDevices", () => {
  it("ignores malformed entries rather than throwing on one bad device", async () => {
    mockFetch([DEVICES[0], { id: "not-a-number", name: "x", slug: "x" }, null]);
    expect(await fetchRingDevices()).toEqual([DEVICES[0]]);
  });
});

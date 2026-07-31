import { describe, it, expect } from "vitest";
import {
  normalizeFrigateEvents,
  parseFrigateWsEvents,
  recordingUrlAt,
  vodPositionSeconds,
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

  describe("parseFrigateWsEvents (frigate/events/get websocket result)", () => {
    // The transport matters here: `GET /api/frigate/events` DOES NOT EXIST — the integration's
    // query API is websocket-only, and the old REST fetch 404'd on every call while its catch
    // returned []. These pin the websocket result shapes so a stubbed-wrong transport can't pass
    // again (the exact failure mode the /api/frigate/config tests document).
    it("unwraps the JSON-string result the integration actually sends (decode_json=False)", () => {
      const events = parseFrigateWsEvents(
        JSON.stringify([{ id: "1", camera: "kitchen", start_time: 1, has_clip: true }]),
      );
      expect(events).toHaveLength(1);
      expect(events[0].camera).toBe("kitchen");
    });

    it("accepts an already-decoded array, in case a future version decodes server-side", () => {
      expect(parseFrigateWsEvents([{ id: "1", start_time: 1 }])).toHaveLength(1);
    });

    it("returns [] for junk rather than throwing — the timeline renders empty, never breaks", () => {
      expect(parseFrigateWsEvents("not json")).toEqual([]);
      expect(parseFrigateWsEvents(null)).toEqual([]);
      expect(parseFrigateWsEvents(undefined)).toEqual([]);
      expect(parseFrigateWsEvents({ events: [] })).toEqual([]);
      expect(parseFrigateWsEvents('{"an":"object"}')).toEqual([]);
    });
  });

  describe("GenAI description", () => {
    const base = { id: "e1", camera: "kitchen", label: "person", start_time: 1_700_000_000 };

    it("maps Frigate's nested data.description", () => {
      const [ev] = normalizeFrigateEvents([
        { ...base, data: { description: "A person walks to the counter." } },
      ]);
      expect(ev.description).toBe("A person walks to the counter.");
    });

    // Null is the NORMAL case, not an error: genai runs for person only, and only
    // after the event ends. One "absent" representation keeps the UI simple.
    it("is null when absent, empty, whitespace, or explicitly null", () => {
      expect(normalizeFrigateEvents([{ ...base }])[0].description).toBeNull();
      expect(normalizeFrigateEvents([{ ...base, data: {} }])[0].description).toBeNull();
      expect(
        normalizeFrigateEvents([{ ...base, data: { description: "" } }])[0].description,
      ).toBeNull();
      expect(
        normalizeFrigateEvents([{ ...base, data: { description: "   \n " } }])[0].description,
      ).toBeNull();
      expect(
        normalizeFrigateEvents([{ ...base, data: { description: null } }])[0].description,
      ).toBeNull();
    });

    it("trims surrounding whitespace", () => {
      const [ev] = normalizeFrigateEvents([{ ...base, data: { description: "  hello  " } }]);
      expect(ev.description).toBe("hello");
    });

    // Real text off the phone: descriptions generated before the Frigate prompt was
    // fixed are multi-paragraph raw markdown, and nothing here renders markdown, so
    // the asterisks and headings showed up literally on screen.
    it("flattens a legacy markdown essay into one line of prose", () => {
      const essay = [
        "Based on the sequence of images, the person's behavior shows a clear",
        "progression related to **cleaning or general household maintenance**.",
        "",
        "**Analysis of Actions and Movement:**",
        "",
        "1.  **Initial/Mid-Sequence (Frames 1-3):** The person is moving toward the",
        "kitchen.",
        "2.  **Late Sequence:** The movement slows slightly.",
        "",
        "## Conclusion on Intent",
        "> The primary intent is to *complete a physical task*.",
      ].join("\n");
      const [ev] = normalizeFrigateEvents([{ ...base, data: { description: essay } }]);

      expect(ev.description).not.toMatch(/[*#>]/);
      expect(ev.description).not.toMatch(/\n/);
      expect(ev.description).toContain("cleaning or general household maintenance");
      expect(ev.description).toContain("Analysis of Actions and Movement:");
      // List markers go, their content stays.
      expect(ev.description).toContain("Initial/Mid-Sequence (Frames 1-3):");
      expect(ev.description).not.toMatch(/\s1\.\s/);
    });

    it("leaves an already-plain sentence untouched", () => {
      const plain = "A person walks through the kitchen carrying towels.";
      const [ev] = normalizeFrigateEvents([{ ...base, data: { description: plain } }]);
      expect(ev.description).toBe(plain);
    });

    // Markup that is ALL syntax must collapse to absent, not to an empty string.
    it("is null when the description is nothing but markup", () => {
      expect(
        normalizeFrigateEvents([{ ...base, data: { description: "**  **" } }])[0].description,
      ).toBeNull();
    });

    // The WS result arrives as a JSON *string*; the description must survive that hop.
    it("survives the JSON-string-wrapped websocket result", () => {
      const wire = JSON.stringify([{ ...base, data: { description: "Carrying a box." } }]);
      const [ev] = normalizeFrigateEvents(parseFrigateWsEvents(wire));
      expect(ev.description).toBe("Carrying a box.");
    });
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

  describe("vodPositionSeconds (scrub seek, not reload)", () => {
    const winStart = 1_700_000_000_000;

    it("maps a scrub time to its in-media offset in seconds", () => {
      expect(vodPositionSeconds(winStart + 90_000, winStart)).toBe(90);
      expect(vodPositionSeconds(winStart, winStart)).toBe(0);
    });

    it("clamps a seek before the window start to 0 (no negative/out-of-range seek crash)", () => {
      expect(vodPositionSeconds(winStart - 5_000, winStart)).toBe(0);
    });

    it("is independent of the VOD URL: the window URL stays the same as the playhead moves, so scrubbing seeks instead of reloading", () => {
      const url = recordingUrlAt("front", winStart, winStart + 86_400_000);
      // Two different scrub positions → same source URL, different seek offsets.
      expect(recordingUrlAt("front", winStart, winStart + 86_400_000)).toBe(url);
      expect(vodPositionSeconds(winStart + 10_000, winStart)).not.toBe(
        vodPositionSeconds(winStart + 20_000, winStart),
      );
    });
  });
});

import { describe, it, expect } from "vitest";
import {
  chooseRecordedSource,
  footageSegmentAt,
  footageSpans,
  isPlayable,
  offsetInSegmentSeconds,
  parseFrigateWsRecordings,
  parseRingFootage,
  type FootageSegment,
} from "../ringFootage";
import type { CameraEvent } from "../cameraEvents";

const T0 = 1_700_000_000_000;
const MIN = 60_000;

function seg(over: Partial<FootageSegment> = {}): FootageSegment {
  return {
    startMs: T0,
    endMs: T0 + 10 * MIN,
    url: "https://ring/footage.mp4",
    urlExpiresAtMs: T0 + 15 * MIN,
    encrypted: false,
    chunked: true,
    dingId: null,
    ...over,
  };
}

function clip(id: string, startMs: number, endMs: number | null): CameraEvent {
  return {
    id,
    camera: "gate",
    label: "motion",
    startMs,
    endMs,
    hasClip: true,
    hasSnapshot: false,
    thumbnailUrl: null,
    snapshotUrl: null,
    description: null,
  };
}

describe("parseRingFootage", () => {
  it("normalizes the service payload and reports the earliest expiry", () => {
    const out = parseRingFootage({
      segments: [
        { startMs: T0 + 20 * MIN, endMs: T0 + 30 * MIN, url: "b", urlExpiresAtMs: T0 + 40 * MIN },
        {
          startMs: T0,
          endMs: T0 + 10 * MIN,
          url: "a",
          urlExpiresAtMs: T0 + 15 * MIN,
          encrypted: false,
          chunked: true,
          dingId: "77",
        },
      ],
      truncated: true,
    });

    // Sorted oldest-first regardless of the order Ring returned them.
    expect(out.segments.map((s) => s.url)).toEqual(["a", "b"]);
    expect(out.continuous).toBe(true);
    expect(out.truncated).toBe(true);
    // The FIRST url to die drives the refresh, not the last.
    expect(out.expiresAtMs).toBe(T0 + 15 * MIN);
    expect(out.segments[0].dingId).toBe("77");
    expect(out.segments[0].chunked).toBe(true);
  });

  it("drops unusable spans without losing the rest", () => {
    const out = parseRingFootage({
      segments: [
        seg({ url: "keep" }),
        { startMs: T0, endMs: T0 }, // zero-length: unseekable, would draw as an invisible sliver
        { startMs: T0 + MIN, endMs: T0 }, // inverted
        { startMs: "nope", endMs: T0 + MIN },
        null,
      ],
    });
    expect(out.segments).toHaveLength(1);
    expect(out.segments[0].url).toBe("keep");
  });

  it("keeps encrypted and URL-less spans — they are real coverage", () => {
    const out = parseRingFootage({
      segments: [seg({ url: null }), seg({ startMs: T0 + 20 * MIN, endMs: T0 + 30 * MIN, encrypted: true })],
    });
    expect(out.segments).toHaveLength(2);
    expect(out.segments.map(isPlayable)).toEqual([false, false]);
    // Coverage exists even though none of it can play — the lane must still draw it.
    expect(out.continuous).toBe(true);
  });

  it("reads a camera with no 24/7 track as not-continuous, not as an error", () => {
    expect(parseRingFootage({ segments: [], continuous: false })).toMatchObject({
      segments: [],
      continuous: false,
      expiresAtMs: null,
    });
    expect(parseRingFootage(null).continuous).toBe(false);
    expect(parseRingFootage({}).segments).toEqual([]);
  });
});

describe("footageSegmentAt", () => {
  const a = seg({ startMs: T0, endMs: T0 + 10 * MIN, url: "a" });
  const b = seg({ startMs: T0 + 10 * MIN, endMs: T0 + 20 * MIN, url: "b" });

  it("contains the start and excludes the end, so a seam belongs to exactly one segment", () => {
    expect(footageSegmentAt([a, b], T0)?.url).toBe("a");
    expect(footageSegmentAt([a, b], T0 + 10 * MIN - 1)?.url).toBe("a");
    // The boundary instant is b's, not both — a closed interval made the player's segment-keyed
    // effects thrash between two sources while scrubbing across the seam.
    expect(footageSegmentAt([a, b], T0 + 10 * MIN)?.url).toBe("b");
  });

  it("returns null outside the covered spans", () => {
    expect(footageSegmentAt([a], T0 - 1)).toBeNull();
    expect(footageSegmentAt([a], T0 + 10 * MIN)).toBeNull();
    expect(footageSegmentAt([], T0)).toBeNull();
  });

  it("picks the latest-starting segment on a genuine overlap", () => {
    const wide = seg({ startMs: T0, endMs: T0 + 60 * MIN, url: "wide" });
    const inner = seg({ startMs: T0 + 5 * MIN, endMs: T0 + 15 * MIN, url: "inner" });
    expect(footageSegmentAt([wide, inner], T0 + 10 * MIN)?.url).toBe("inner");
  });
});

describe("offsetInSegmentSeconds", () => {
  const s = seg({ startMs: T0, endMs: T0 + 10 * MIN });

  it("is the distance into the segment, in seconds", () => {
    expect(offsetInSegmentSeconds(s, T0 + 90_000)).toBe(90);
  });

  it("clamps into the span so a rounding overshoot can't seek past the media", () => {
    expect(offsetInSegmentSeconds(s, T0 - 5000)).toBe(0);
    expect(offsetInSegmentSeconds(s, T0 + 99 * MIN)).toBe(600);
  });
});

describe("footageSpans", () => {
  it("coalesces abutting segments so a stitch seam doesn't read as a gap", () => {
    const spans = footageSpans([
      seg({ startMs: T0, endMs: T0 + 10 * MIN }),
      seg({ startMs: T0 + 10 * MIN, endMs: T0 + 25 * MIN }),
    ]);
    expect(spans).toEqual([{ startMs: T0, endMs: T0 + 25 * MIN, playable: true }]);
  });

  it("keeps a real gap as a real gap", () => {
    const spans = footageSpans([
      seg({ startMs: T0, endMs: T0 + 10 * MIN }),
      seg({ startMs: T0 + 40 * MIN, endMs: T0 + 50 * MIN }),
    ]);
    expect(spans).toHaveLength(2);
  });

  it("never merges playable footage into an unplayable run", () => {
    const spans = footageSpans([
      seg({ startMs: T0, endMs: T0 + 10 * MIN }),
      seg({ startMs: T0 + 10 * MIN, endMs: T0 + 20 * MIN, encrypted: true }),
      seg({ startMs: T0 + 20 * MIN, endMs: T0 + 30 * MIN }),
    ]);
    expect(spans.map((s) => s.playable)).toEqual([true, false, true]);
  });
});

describe("parseFrigateWsRecordings (frigate/recordings/get websocket result)", () => {
  // Same websocket-only contract as frigate/events/get: there is no REST route for recordings,
  // and the result usually arrives as a JSON STRING the integration didn't decode.
  const seg = (start: number, end: number) => ({ start_time: start, end_time: end });

  it("unwraps the JSON-string result and coalesces contiguous ~10s segments into one span", () => {
    const spans = parseFrigateWsRecordings(
      JSON.stringify([seg(1000, 1010), seg(1010.2, 1020), seg(1020.1, 1030)]),
    );
    expect(spans).toEqual([{ startMs: 1000_000, endMs: 1030_000, playable: true }]);
  });

  it("keeps a real gap (beyond tolerance) as two spans — the lane must show honest holes", () => {
    const spans = parseFrigateWsRecordings([seg(1000, 1010), seg(1100, 1110)]);
    expect(spans).toHaveLength(2);
    expect(spans[0].endMs).toBe(1010_000);
    expect(spans[1].startMs).toBe(1100_000);
  });

  it("bridges a single dropped segment (a hole within tolerance)", () => {
    // One missing ~10s cache segment is real but not worth drawing as a gap.
    const spans = parseFrigateWsRecordings([seg(1000, 1010), seg(1022, 1032)]);
    expect(spans).toHaveLength(1);
  });

  it("sorts unordered input before coalescing", () => {
    const spans = parseFrigateWsRecordings([seg(1020, 1030), seg(1000, 1010), seg(1010, 1020)]);
    expect(spans).toEqual([{ startMs: 1000_000, endMs: 1030_000, playable: true }]);
  });

  it("drops malformed entries and returns [] for junk rather than throwing", () => {
    expect(parseFrigateWsRecordings("not json")).toEqual([]);
    expect(parseFrigateWsRecordings(null)).toEqual([]);
    expect(parseFrigateWsRecordings({ recordings: [] })).toEqual([]);
    // end <= start and missing fields drop only themselves.
    const spans = parseFrigateWsRecordings([seg(1000, 1010), seg(2000, 2000), { start_time: 3000 }]);
    expect(spans).toEqual([{ startMs: 1000_000, endMs: 1010_000, playable: true }]);
  });
});



describe("chooseRecordedSource", () => {
  const events = [clip("m1", T0 + 5 * MIN, T0 + 6 * MIN)];
  const urls = new Map([["m1", "https://ring/clip.mp4"]]);
  const base = { events, urls, loadedClipId: null, loadedDurationMs: null };

  it("plays the continuous track even where an event clip also covers the moment", () => {
    // The whole window is one media source, so scrubbing seeks instead of re-initialising the
    // player per clip. The event stays drawn on the timeline as a marker either way.
    const out = chooseRecordedSource({
      ...base,
      headMs: T0 + 5 * MIN + 30_000,
      segments: [seg({ startMs: T0, endMs: T0 + 60 * MIN, url: "cont" })],
    });
    expect(out).toMatchObject({ kind: "footage", url: "cont", seekSeconds: 330 });
  });

  it("falls back to the event clip where the continuous track has a gap", () => {
    const out = chooseRecordedSource({
      ...base,
      headMs: T0 + 5 * MIN + 30_000,
      segments: [seg({ startMs: T0 + 40 * MIN, endMs: T0 + 50 * MIN })],
    });
    expect(out).toMatchObject({ kind: "clip", url: "https://ring/clip.mp4", seekSeconds: 30 });
  });

  it("uses the event clip rather than reporting encrypted when both cover the moment", () => {
    const out = chooseRecordedSource({
      ...base,
      headMs: T0 + 5 * MIN + 30_000,
      segments: [seg({ startMs: T0, endMs: T0 + 60 * MIN, encrypted: true })],
    });
    expect(out.kind).toBe("clip");
  });

  it("reports encrypted only when there is nothing else to play", () => {
    const out = chooseRecordedSource({
      ...base,
      headMs: T0 + 30 * MIN,
      segments: [seg({ startMs: T0, endMs: T0 + 60 * MIN, encrypted: true })],
    });
    expect(out).toEqual({ kind: "encrypted" });
  });

  it("distinguishes 'nothing recorded' from every other outcome", () => {
    expect(chooseRecordedSource({ ...base, headMs: T0 + 30 * MIN, segments: [] })).toEqual({
      kind: "none",
    });
  });

  it("won't play an event whose URL never came down with the timeline", () => {
    const out = chooseRecordedSource({
      ...base,
      urls: new Map(),
      headMs: T0 + 5 * MIN + 30_000,
      segments: [],
    });
    expect(out).toEqual({ kind: "none" });
  });

  it("resolves an open-ended clip's span from the loaded media duration", () => {
    const openEnded = [clip("m1", T0 + 5 * MIN, null)];
    const at = T0 + 5 * MIN + 45_000;
    // 30s is the assumed span, so without the loaded duration this moment is past the clip's end.
    expect(
      chooseRecordedSource({ ...base, events: openEnded, headMs: at, segments: [] }).kind,
    ).toBe("none");
    expect(
      chooseRecordedSource({
        ...base,
        events: openEnded,
        headMs: at,
        segments: [],
        loadedClipId: "m1",
        loadedDurationMs: 90_000,
      }),
    ).toMatchObject({ kind: "clip", seekSeconds: 45 });
  });
});

import { describe, it, expect } from "vitest";
import {
  QUICK_REPLIES,
  canReachSpeaker,
  quickReplyPath,
  replyUrl,
  type QuickReply,
} from "../quickReply";

/**
 * The reply URL is where this feature silently breaks.
 *
 * The source go2rtc needs contains `:`, `/` and a `#` fragment. Leave any of them raw and the
 * query is truncated at the `#` — go2rtc then receives a source with no codec directive, or none
 * at all, and either transcodes wrongly or fails. Neither is visible from the app: you tap Reply,
 * something happens on the server, and nobody speaks.
 *
 * Mirrors `QuickReplyTest.kt`.
 */
describe("quickReplyPath", () => {
  it("encodes the fragment instead of letting it truncate the query", () => {
    const path = quickReplyPath("front_door", QUICK_REPLIES[0]);
    expect(path).not.toContain("#"); // a raw # ends the query at the codec directive
    expect(path).toContain("%23audio%3Dpcmu");
  });

  it("encodes the path separators in the source", () => {
    const path = quickReplyPath("garage", { id: "t", label: "t", file: "hello.wav" });
    expect(path).toContain("ffmpeg%3A%2Fconfig%2Freplies%2Fhello.wav");
    // The query's own separators must survive as themselves.
    expect(path.startsWith("go2rtc/api/streams?dst=garage&src=")).toBe(true);
  });

  it("never turns a space into a plus", () => {
    // A `+` would be read by go2rtc as a literal plus in a filename.
    const path = quickReplyPath("front door", { id: "t", label: "t", file: "be right there.wav" });
    expect(path).not.toContain("+");
    expect(path).toContain("front%20door");
    expect(path).toContain("be%20right%20there.wav");
  });

  it("encodes the characters encodeURIComponent would leave raw", () => {
    // The Kotlin twin's unreserved set is A-Za-z0-9-_.~ — `!*'()` are NOT in it. If this
    // diverged, the two clients would build different URLs for the same reply.
    const path = quickReplyPath("cam", { id: "t", label: "t", file: "hi!'(*).wav" });
    for (const raw of ["!", "'", "(", ")", "*"]) expect(path).not.toContain(raw);
  });

  it("requests PCMU, because that is what the backchannel advertises", () => {
    // The cameras' SDP carries `a=rtpmap:0 PCMU/8000` on their sendonly track. Asking for
    // anything else makes go2rtc transcode at best and fail at worst.
    expect(quickReplyPath("nursery", QUICK_REPLIES[1])).toContain("audio%3Dpcmu");
  });

  it("builds a same-origin URL under the nginx go2rtc mount", () => {
    expect(replyUrl("front_door_reolink", QUICK_REPLIES[0])).toMatch(
      /^\/go2rtc\/api\/streams\?dst=front_door_reolink&src=/,
    );
  });
});

describe("QUICK_REPLIES", () => {
  it("stays few, short, and never claims the house is empty", () => {
    expect(QUICK_REPLIES.length).toBeLessThanOrEqual(4);
    for (const r of QUICK_REPLIES) {
      expect(r.label.length).toBeLessThanOrEqual(45);
      // A stranger hears these. None should confirm nobody is home.
      for (const giveaway of ["not home", "away", "nobody is home", "out of town"]) {
        expect(r.label.toLowerCase()).not.toContain(giveaway);
      }
    }
  });

  it("gives every reply a distinct id and file", () => {
    const ids = new Set(QUICK_REPLIES.map((r: QuickReply) => r.id));
    const files = new Set(QUICK_REPLIES.map((r: QuickReply) => r.file));
    expect(ids.size).toBe(QUICK_REPLIES.length);
    expect(files.size).toBe(QUICK_REPLIES.length);
  });

  it("matches the Android set exactly, so both clients offer the same three", () => {
    expect(QUICK_REPLIES.map((r) => [r.id, r.file])).toEqual([
      ["leave", "leave-at-door.wav"],
      ["coming", "be-right-there.wav"],
      ["cant", "cant-come.wav"],
    ]);
  });
});

describe("canReachSpeaker", () => {
  it("fails closed", () => {
    expect(canReachSpeaker(true)).toBe(true);
    expect(canReachSpeaker(false)).toBe(false);
    // Unknown — the go2rtc stream list is still in flight. No button rather than a button
    // that might do nothing.
    expect(canReachSpeaker(null)).toBe(false);
  });

  it("does not depend on a camera being Ring", () => {
    // The point of the 2026-08-05 change: this predicate answers "can go2rtc reach a speaker
    // here", which is true for a Reolink go2rtc serves and false for a Ring camera it does not.
    // A gate that consulted the camera's KIND could not express either.
    expect(canReachSpeaker(true)).toBe(true);
    expect(canReachSpeaker(false)).toBe(false);
  });
});

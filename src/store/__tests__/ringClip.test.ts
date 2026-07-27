import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { resolveRingClipUrl } from "../ringClip";
import { useEntityStore } from "../entityStore";
import * as connection from "../connection";
import type { HassEntity } from "../../lib/ha";

vi.mock("../connection", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../connection")>()),
  callService: vi.fn(async () => {}),
  streamUrl: vi.fn(async () => null),
}));

const callServiceMock = vi.mocked(connection.callService);
const streamUrlMock = vi.mocked(connection.streamUrl);

const SELECT = "select.gate_event_select";
const OPTIONS = ["Motion 1", "Motion 2"];

/** Put the selector in the store as ring-mqtt would publish it. */
function publish(state: string, recordingUrl?: string | null) {
  useEntityStore.setState({
    entities: {
      [SELECT]: {
        entity_id: SELECT,
        state,
        attributes: { options: OPTIONS, ...(recordingUrl != null ? { recordingUrl } : {}) },
      } as HassEntity,
    },
  });
}

beforeEach(() => {
  callServiceMock.mockReset();
  callServiceMock.mockResolvedValue(undefined);
  streamUrlMock.mockReset();
  streamUrlMock.mockResolvedValue(null);
  publish("Motion 1", "https://ring.example/old.mp4");
});

afterEach(() => {
  vi.useRealTimers();
});

describe("resolveRingClipUrl", () => {
  it("selects the option and returns the URL ring-mqtt then publishes for it", async () => {
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2" });
    await vi.waitFor(() =>
      expect(callServiceMock).toHaveBeenCalledWith("select", "select_option", {
        entity_id: SELECT,
        option: "Motion 2",
      }),
    );

    publish("Motion 2", "https://ring.example/new.mp4");
    await expect(pending).resolves.toBe("https://ring.example/new.mp4");
  });

  it("ignores the previous clip's URL while the new one is still being fetched", async () => {
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2" });
    // HA delivers the new state before ring-mqtt's attribute update lands.
    publish("Motion 2", "https://ring.example/old.mp4");
    const settled = await Promise.race([pending, Promise.resolve("still-waiting")]);
    expect(settled).toBe("still-waiting");

    publish("Motion 2", "https://ring.example/new.mp4");
    await expect(pending).resolves.toBe("https://ring.example/new.mp4");
  });

  it("uses the already-published URL when the option is already selected", async () => {
    await expect(
      resolveRingClipUrl({ selectId: SELECT, option: "Motion 1" }),
    ).resolves.toBe("https://ring.example/old.mp4");
  });

  it("fails fast when ring-mqtt says there is no recording", async () => {
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2" });
    publish("Motion 2", "<Recording Not Found>");
    await expect(pending).resolves.toBeNull();
  });

  it("keeps waiting through a transcode, then plays it", async () => {
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2" });
    publish("Motion 2", "<Transcoding in Progress>");
    const settled = await Promise.race([pending, Promise.resolve("still-waiting")]);
    expect(settled).toBe("still-waiting");

    publish("Motion 2", "https://ring.example/transcoded.mp4");
    await expect(pending).resolves.toBe("https://ring.example/transcoded.mp4");
  });

  it("gives up on the deadline instead of hanging — a failure the player can retry", async () => {
    vi.useFakeTimers();
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2", timeoutMs: 50 });
    await vi.advanceTimersByTimeAsync(60);
    await expect(pending).resolves.toBeNull();
  });

  // ring-mqtt leaves the previous clip's URL in place when its event lookup finds nothing new
  // (the 24/7 cameras do this constantly), so an unchanged URL must never be served as this clip.
  it("fails rather than serving a URL that never changed — it may be another event's clip", async () => {
    vi.useFakeTimers();
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2", timeoutMs: 50 });
    publish("Motion 2", "https://ring.example/old.mp4");
    await vi.advanceTimersByTimeAsync(60);
    await expect(pending).resolves.toBeNull();
  });

  it("the retry then plays it, because the option is active by then (one Ring event, two options)", async () => {
    publish("Motion 2", "https://ring.example/old.mp4");
    await expect(
      resolveRingClipUrl({ selectId: SELECT, option: "Motion 2", timeoutMs: 50 }),
    ).resolves.toBe("https://ring.example/old.mp4");
  });

  it("a rejected select_option still yields whatever is published (best-effort select)", async () => {
    callServiceMock.mockRejectedValueOnce(new Error("option rotated out"));
    const pending = resolveRingClipUrl({ selectId: SELECT, option: "Motion 2" });
    publish("Motion 2", "https://ring.example/new.mp4");
    await expect(pending).resolves.toBe("https://ring.example/new.mp4");
  });

  it("uses the legacy `_event` camera stream when that entity exists (ring-mqtt 4.x)", async () => {
    streamUrlMock.mockResolvedValue("/api/hls/token/master.m3u8");
    await expect(
      resolveRingClipUrl({
        selectId: SELECT,
        option: "Motion 2",
        eventStreamId: "camera.gate_event",
      }),
    ).resolves.toBe("/api/hls/token/master.m3u8");
    expect(streamUrlMock).toHaveBeenCalledWith("camera.gate_event");
  });
});

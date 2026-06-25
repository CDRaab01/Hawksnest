import { describe, it, expect } from "vitest";
import {
  snapshotUrl,
  snapshotUrlAt,
  streamUrl,
  isCameraLive,
} from "../cameraUrl";
import type { HassEntity } from "../ha";

const cam = (over: Partial<HassEntity> = {}): HassEntity => ({
  entity_id: "camera.front_door",
  state: "idle",
  attributes: {
    entity_picture: "/api/camera_proxy/camera.front_door?token=abc123",
  },
  ...over,
});

describe("cameraUrl", () => {
  it("reads the signed snapshot URL off entity_picture", () => {
    expect(snapshotUrl(cam())).toBe(
      "/api/camera_proxy/camera.front_door?token=abc123",
    );
  });

  it("returns null when there is no entity_picture (demo mode)", () => {
    expect(snapshotUrl(cam({ attributes: {} }))).toBeNull();
    expect(streamUrl(cam({ attributes: {} }))).toBeNull();
  });

  it("appends a cache-buster with the right separator", () => {
    expect(snapshotUrlAt(cam(), 42)).toBe(
      "/api/camera_proxy/camera.front_door?token=abc123&_=42",
    );
    const noQuery = cam({
      attributes: { entity_picture: "/api/camera_proxy/camera.x" },
    });
    expect(snapshotUrlAt(noQuery, 7)).toBe("/api/camera_proxy/camera.x?_=7");
  });

  it("derives the MJPEG stream URL, reusing the token", () => {
    expect(streamUrl(cam())).toBe(
      "/api/camera_proxy_stream/camera.front_door?token=abc123",
    );
  });

  it("resolves snapshot + stream against the connected HA origin", () => {
    const base = "http://192.168.4.34:8123";
    expect(snapshotUrl(cam(), base)).toBe(
      "http://192.168.4.34:8123/api/camera_proxy/camera.front_door?token=abc123",
    );
    expect(snapshotUrlAt(cam(), 9, base)).toBe(
      "http://192.168.4.34:8123/api/camera_proxy/camera.front_door?token=abc123&_=9",
    );
    expect(streamUrl(cam(), base)).toBe(
      "http://192.168.4.34:8123/api/camera_proxy_stream/camera.front_door?token=abc123",
    );
  });

  it("trims a trailing slash on the base and leaves absolute pictures alone", () => {
    expect(snapshotUrl(cam(), "http://ha.local:8123/")).toBe(
      "http://ha.local:8123/api/camera_proxy/camera.front_door?token=abc123",
    );
    const absolute = cam({
      attributes: {
        entity_picture: "https://nabu.example/api/camera_proxy/camera.x?token=z",
      },
    });
    expect(snapshotUrl(absolute, "http://ha.local:8123")).toBe(
      "https://nabu.example/api/camera_proxy/camera.x?token=z",
    );
  });

  it("gates availability on a signed URL and a live state", () => {
    expect(isCameraLive(cam())).toBe(true);
    expect(isCameraLive(cam({ state: "unavailable" }))).toBe(false);
    expect(isCameraLive(cam({ attributes: {} }))).toBe(false);
  });
});

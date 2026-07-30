import { describe, it, expect } from "vitest";
import { FRIGATE_RETENTION_DAYS, frigateCameraName, isFrigateCamera } from "../frigate";
import type { HassEntity } from "../ha";

/**
 * These replace the old config-fetch tests, which stubbed `/api/frigate/config`.
 *
 * That route does not exist — frigate-hass-integration never proxied it, so the real request 404s
 * and every camera resolved as "no Frigate", silently disabling recorded playback. The old tests
 * passed precisely because they stubbed a route that was never real, which is why they never
 * caught it. Membership now comes from the `camera.*` entity the integration creates, which
 * stamps `client_id` and `camera_name` onto it.
 */
function entity(attributes: Record<string, unknown>): HassEntity {
  return {
    entity_id: "camera.big_room",
    state: "recording",
    attributes,
  } as unknown as HassEntity;
}

describe("isFrigateCamera", () => {
  it("recognises a camera the Frigate integration created", () => {
    expect(isFrigateCamera(entity({ client_id: "frigate", camera_name: "big_room" }))).toBe(true);
  });

  // The whole point of failing closed: a camera wrongly believed to be on Frigate stops looping
  // the demo clip and starts surfacing errors for recordings that never existed.
  it("fails closed for a non-Frigate camera", () => {
    expect(isFrigateCamera(entity({ friendly_name: "Front Door" }))).toBe(false);
  });

  it("fails closed for null/undefined rather than throwing", () => {
    expect(isFrigateCamera(null)).toBe(false);
    expect(isFrigateCamera(undefined)).toBe(false);
  });

  it("requires BOTH markers, so another integration using one name cannot match", () => {
    expect(isFrigateCamera(entity({ client_id: "frigate" }))).toBe(false);
    expect(isFrigateCamera(entity({ camera_name: "big_room" }))).toBe(false);
  });

  it("ignores non-string markers", () => {
    expect(isFrigateCamera(entity({ client_id: 1, camera_name: true }))).toBe(false);
  });
});

describe("frigateCameraName", () => {
  it("returns what Frigate calls the camera, which is authoritative over the entity id", () => {
    // Every VOD/event URL must use this, not the HA slug, or the request 404s.
    expect(frigateCameraName(entity({ camera_name: "big_room" }))).toBe("big_room");
  });

  it("is null when absent or empty", () => {
    expect(frigateCameraName(entity({}))).toBeNull();
    expect(frigateCameraName(entity({ camera_name: "" }))).toBeNull();
    expect(frigateCameraName(null)).toBeNull();
  });
});

describe("FRIGATE_RETENTION_DAYS", () => {
  it("is a positive number of days", () => {
    // Not discoverable at runtime — must track record.continuous.days in the Frigate seed by hand.
    expect(FRIGATE_RETENTION_DAYS).toBeGreaterThan(0);
  });
});

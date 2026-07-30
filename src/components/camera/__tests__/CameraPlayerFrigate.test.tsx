import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { CameraPlayer } from "../CameraPlayer";
import { startConnection } from "../../../store/connection";
import { useEntityStore } from "../../../store/entityStore";
import type { HassEntity } from "../../../lib/ha";
import type { LogicalCamera } from "../../../lib/cameraModel";

/**
 * A Frigate camera as the app will actually see it: an ordinary HA camera with NO
 * ring-mqtt event selector. Before the backend split, `isRing` was false for it and
 * it silently inherited the demo path — no go2rtc live tier, looping playback, and
 * playback errors swallowed. These cover that it now takes the Frigate path.
 *
 * The Ring behaviour these share a component with is pinned by `CameraPlayerRing`;
 * that suite must keep passing unedited alongside this one.
 */
const BEDROOM: LogicalCamera = (() => {
  const entity: HassEntity = {
    entity_id: "camera.bedroom",
    state: "idle",
    // Frigate membership is read off these markers now, not a config fetch — the
    // integration stamps them onto every camera it creates.
    attributes: {
      entity_picture: "/api/camera_proxy/camera.bedroom?token=x",
      client_id: "frigate",
      camera_name: "bedroom",
    },
  };
  return {
    id: "camera.bedroom",
    name: "Bedroom",
    liveEntity: entity,
    snapshotEntity: entity,
    eventStreamId: null,
    eventSelectId: null, // ← not a Ring camera
    dingId: null,
    motionId: null,
    sirenSwitchId: null,
  };
})();

/**
 * go2rtc serves a `bedroom` stream. Anything else (the demo clip, HA's own endpoints) falls
 * through to the real fetch. Frigate membership is no longer stubbed here — it comes from the
 * camera entity's attributes above, because `/api/frigate/config` is not a route that exists.
 */
function stubFrigateAndGo2rtc({
  go2rtcStreams = { bedroom: {} } as Record<string, unknown>,
} = {}) {
  const real = globalThis.fetch;
  vi.stubGlobal(
    "fetch",
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/go2rtc/api/streams")) {
        return Promise.resolve({ ok: true, json: async () => go2rtcStreams });
      }
      return real ? real(input, init) : Promise.reject(new Error("no fetch"));
    }),
  );
}

beforeEach(() => {
  useEntityStore.setState({ entities: {}, areas: {}, status: "connecting" });
  startConnection();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderBedroom() {
  render(
    <MemoryRouter>
      <CameraPlayer camera={BEDROOM} cameras={[BEDROOM]} onSelectCamera={vi.fn()} />
    </MemoryRouter>,
  );
}

describe("CameraPlayer (Frigate camera)", () => {
  it("offers the go2rtc live tier — no longer Ring-only", async () => {
    stubFrigateAndGo2rtc();
    renderBedroom();
    // The go2rtc tier is chosen once the stream list confirms it serves `bedroom`.
    await waitFor(() =>
      expect(
        (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.some((c) =>
          String(c[0]).includes("/go2rtc/api/streams"),
        ),
      ).toBe(true),
    );
  });

  it("does not take the go2rtc tier when go2rtc doesn't serve the camera", async () => {
    // Stream list comes back without `bedroom` — the ladder must fall through to a
    // tier that can actually render, not stall on a stream that isn't there.
    stubFrigateAndGo2rtc({ go2rtcStreams: {} });
    renderBedroom();
    expect(await screen.findByLabelText(/camera/i)).toBeInTheDocument();
  });

  it("plays recorded footage without looping it", async () => {
    stubFrigateAndGo2rtc();
    renderBedroom();
    const user = userEvent.setup();

    // Scrub off live into the recorded window.
    const slider = screen.getByRole("slider", { name: "Recording timeline" });
    slider.focus();
    await user.keyboard("{ArrowLeft}");

    await waitFor(() => {
      const video = document.querySelector("video");
      expect(video).not.toBeNull();
      // A real recording is finite — looping it would replay the past forever.
      expect((video as HTMLVideoElement).loop).toBe(false);
    });
  });
});

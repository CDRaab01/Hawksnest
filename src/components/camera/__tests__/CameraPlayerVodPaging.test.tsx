import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { CameraPlayer } from "../CameraPlayer";
import {
  fetchCameraFootage,
  signedRecordingUrlAt,
  startConnection,
} from "../../../store/connection";
import { useEntityStore } from "../../../store/entityStore";
import { resetGo2rtcForTest } from "../../../lib/go2rtc";
import type { HassEntity } from "../../../lib/ha";
import type { LogicalCamera } from "../../../lib/cameraModel";

/**
 * The Frigate recorded path's two "state that must not re-key the player" rules.
 *
 * Both regressions this pins were invisible in review and loud in use:
 *  - the signing effect took the *page object* as a dependency, so every pointer move of a scrub
 *    blanked the video and fired another `auth/sign_path`;
 *  - clip-export state outlived the camera it was drawn on.
 */
vi.mock("../../../store/connection", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../../store/connection")>();
  return {
    ...actual,
    signedRecordingUrlAt: vi.fn(actual.signedRecordingUrlAt),
    fetchCameraFootage: vi.fn(actual.fetchCameraFootage),
  };
});

const signed = signedRecordingUrlAt as unknown as ReturnType<typeof vi.fn>;
const footage = fetchCameraFootage as unknown as ReturnType<typeof vi.fn>;

function frigateCamera(base: string, name: string): LogicalCamera {
  const entity: HassEntity = {
    entity_id: `camera.${base}`,
    state: "idle",
    attributes: {
      entity_picture: `/api/camera_proxy/camera.${base}?token=x`,
      client_id: "frigate",
      camera_name: base,
    },
  };
  return {
    id: `camera.${base}`,
    name,
    liveEntity: entity,
    snapshotEntity: entity,
    eventStreamId: null,
    eventSelectId: null,
    ringSelectorLive: false,
    dingId: null,
    motionId: null,
    sirenSwitchId: null,
    batteryId: null,
  };
}

const BEDROOM = frigateCamera("bedroom", "Bedroom");
const KITCHEN = frigateCamera("kitchen", "Kitchen");

/**
 * 15:00Z, which sits in the MIDDLE of a VOD page.
 *
 * Pages are grid-aligned on epoch multiples of `VOD_PAGE_MS` (2 h), so boundaries fall on even
 * UTC hours. Pinning the clock here means the scrubs below stay inside one page — a real page
 * turn genuinely is a second signature, and a test that straddled a boundary would be flaky
 * rather than wrong.
 */
const NOON_ISH = new Date("2026-08-12T15:00:00Z");

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(NOON_ISH);
  signed.mockClear();
  footage.mockClear();
  resetGo2rtcForTest();
  useEntityStore.setState({ entities: {}, areas: {}, status: "connecting" });
  startConnection();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function renderPlayer(camera: LogicalCamera, cameras: LogicalCamera[] = [camera]) {
  return render(
    <MemoryRouter>
      <CameraPlayer camera={camera} cameras={cameras} onSelectCamera={vi.fn()} />
    </MemoryRouter>,
  );
}

/** One keyboard scrub step back. jsdom has no layout, so a step is one minute. */
function scrubBack(times: number) {
  const track = screen.getByRole("slider", { name: "Recording timeline" });
  track.focus();
  for (let i = 0; i < times; i += 1) {
    fireEvent.keyDown(track, { key: "ArrowLeft" });
  }
}

/**
 * Back to live via the timeline's End key.
 *
 * Deliberately not a "Go live" button: the export bar REPLACES the transport bar, so while a
 * selection is up there is no such button — which is the whole reason the bar leaking past the
 * live edge stranded the user. The timeline is the one route out that stays on screen.
 */
function goLiveFromTrack() {
  const track = screen.getByRole("slider", { name: "Recording timeline" });
  track.focus();
  fireEvent.keyDown(track, { key: "End" });
}

/** The recorded player, once its signed page has resolved. */
async function recordedVideo(): Promise<HTMLElement> {
  await waitFor(() => expect(signed).toHaveBeenCalled());
  return waitFor(() => screen.getByLabelText("Camera footage"));
}

describe("CameraPlayer — Frigate VOD paging", () => {
  it("never signs the same page twice, however many times you scrub", async () => {
    renderPlayer(BEDROOM);

    const PRESSES = 12;
    scrubBack(PRESSES);
    await recordedVideo();

    // Asserting on DISTINCT pages rather than a call count, on purpose: one keyboard step is the
    // on-screen tick interval, so how many steps fit in a page depends on zoom and on where the
    // clock sits in the 2 h grid. Crossing into a new page genuinely is a new signature. What
    // must never happen is signing a page we already signed — that is the re-prepare this
    // paging exists to avoid, and it is what the page OBJECT in the dep array caused.
    const pages = signed.mock.calls.map((c) => `${c[1]}-${c[2]}`);
    expect(pages.length).toBeGreaterThan(0);
    expect(new Set(pages).size).toBe(pages.length);
    // And the effect is driven by the page, not by the playhead: far fewer signatures than steps.
    expect(pages.length).toBeLessThan(PRESSES);
  });

  it("keeps the recorded player mounted across a scrub inside the page", async () => {
    renderPlayer(BEDROOM);
    scrubBack(1);
    const video = await recordedVideo();
    const callsAfterFirstPage = signed.mock.calls.length;

    // Two more steps: comfortably inside the same page at any plausible step size.
    scrubBack(2);
    // Same element: the source was seeked, not torn down and re-signed. When the page object
    // was a dependency this went null → placeholder → new element on every step.
    expect(screen.getByLabelText("Camera footage")).toBe(video);
    expect(signed.mock.calls.length).toBe(callsAfterFirstPage);
    expect(screen.queryByText(/no saved recording|couldn't load/i)).toBeNull();
  });
});

/**
 * The footage lane is no longer decoration. On an event-only camera (a battery Reolink behind a
 * Home Hub, recorded only while its PIR holds it awake) most of the timeline is gap, and Frigate's
 * VOD cannot express a gap — it concatenates whatever exists, or 404s. So the lane decides whether
 * and how much VOD to mount. These pin the two halves of `vodRangeFor` through the real player.
 */
describe("CameraPlayer — gap-aware scrub (event-only cameras)", () => {
  /** Let the lane's resolved spans land in state before scrubbing into them. */
  async function laneLanded() {
    await waitFor(() => expect(footage).toHaveBeenCalled());
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
  }

  it("says 'no saved recording' and signs nothing when the lane says the moment is a gap", async () => {
    const now = NOON_ISH.getTime();
    // Footage exists — two to three hours ago — but not under a playhead one minute back.
    footage.mockResolvedValueOnce([
      { startMs: now - 3 * 3600_000, endMs: now - 2 * 3600_000, playable: true },
    ]);
    renderPlayer(BEDROOM);
    await laneLanded();
    scrubBack(1);

    expect(await screen.findByText("No saved recording for this moment")).toBeInTheDocument();
    // Not a failure with a Retry that can never succeed — and no URL was ever minted for it.
    expect(screen.queryByText(/couldn't load/i)).toBeNull();
    expect(signed).not.toHaveBeenCalled();
  });

  it("bounds the VOD to the footage island under the playhead, not the whole grid page", async () => {
    const now = NOON_ISH.getTime();
    // One keyboard step in jsdom is the strip's tick interval — 15 minutes — so the island must
    // reach back past that; 50 minutes keeps it inside the 14:00–15:00 page while being far
    // narrower than the page, which is what makes the bounded range observable.
    const island = { startMs: now - 50 * 60_000, endMs: now, playable: true };
    footage.mockResolvedValueOnce([island]);
    renderPlayer(BEDROOM);
    await laneLanded();
    scrubBack(1);

    await recordedVideo();
    // The signed range is the island, so playlist time == wall-clock offset from its start.
    expect(signed).toHaveBeenCalledWith("bedroom", island.startMs, island.endMs);
  });
});

describe("CameraPlayer — clip export mode is scoped to one camera and to recorded time", () => {
  it("hides the export bar and restores the transport when you go live", async () => {
    renderPlayer(BEDROOM);
    scrubBack(1);
    await recordedVideo();

    fireEvent.click(screen.getByLabelText("Export a clip"));
    expect(await screen.findByRole("button", { name: /download/i })).toBeInTheDocument();
    // The export bar REPLACES the transport, so while it is up there is no play/prev/next.
    expect(screen.queryByLabelText("Previous moment")).toBeNull();

    goLiveFromTrack();

    // There is nothing to export from the future, and stranding the user without a transport
    // bar was the actual harm.
    await waitFor(() => expect(screen.queryByRole("button", { name: /download/i })).toBeNull());
    expect(screen.getByLabelText("Previous moment")).toBeInTheDocument();
  });

  it("drops the selection when the camera changes", async () => {
    const { rerender } = renderPlayer(BEDROOM, [BEDROOM, KITCHEN]);
    scrubBack(1);
    await recordedVideo();

    fireEvent.click(screen.getByLabelText("Export a clip"));
    expect(await screen.findByRole("button", { name: /download/i })).toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <CameraPlayer camera={KITCHEN} cameras={[BEDROOM, KITCHEN]} onSelectCamera={vi.fn()} />
      </MemoryRouter>,
    );

    // A range is a range on ONE camera's timeline. Carried over, Download asked Frigate to cut
    // that range out of the new camera.
    await waitFor(() => expect(screen.queryByRole("button", { name: /download/i })).toBeNull());
  });
});

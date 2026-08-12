import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { CameraPlayer } from "../CameraPlayer";
import { signedRecordingUrlAt, startConnection } from "../../../store/connection";
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
  return { ...actual, signedRecordingUrlAt: vi.fn(actual.signedRecordingUrlAt) };
});

const signed = signedRecordingUrlAt as unknown as ReturnType<typeof vi.fn>;

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
    dingId: null,
    motionId: null,
    sirenSwitchId: null,
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

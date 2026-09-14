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
import { parseFrigateWsRecordings, type FootageSpan } from "../../../lib/ringFootage";
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

function frigateCamera(base: string, name: string, { battery = false } = {}): LogicalCamera {
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
    // `sensor.<base>_battery` is the tell that a camera SLEEPS (`isBatteryCamera`).
    batteryId: battery ? `sensor.${base}_battery` : null,
  };
}

const BEDROOM = frigateCamera("bedroom", "Bedroom");
const KITCHEN = frigateCamera("kitchen", "Kitchen");
/** A battery Reolink behind the Home Hub: a Frigate camera that is parked `idle` between wakes. */
const FRONT = frigateCamera("front", "Front", { battery: true });

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

  it("mounts the contiguous RUN under the playhead, not the drawn span — the real `front` island", async () => {
    const now = NOON_ISH.getTime();
    // One keyboard step is the strip's tick interval — 15 minutes at the default 1 h viewport
    // the test setup's stubbed layout yields — so the scrubbed moment is now − 15 min. Frigate's
    // recordings table for `front` on 2026-09-13 (local start, duration s), re-based so that
    // 20:47:30 — the moment scrubbed to — lands on it:
    //   20:43:42 0.38 | 20:44:25 0.04 | 20:44:48 2.27 | 20:45:15 10.06 | 20:45:24 14.79
    //   20:46:31 9.92 | 20:47:14 1.75 | 20:47:24 15.46
    // The hub's watchdog restarts ffmpeg around a wake, so the island is scraps and holes.
    const head = now - 15 * 60_000;
    const base = head - 228_000; // 20:43:42 in this frame; 20:47:30 is +228 s
    const row = (offsetS: number, durationS: number) => {
      const start = (base + offsetS * 1000) / 1000;
      return { start_time: start, end_time: start + durationS };
    };
    const spans = parseFrigateWsRecordings([
      row(0, 0.38),
      row(43, 0.04),
      row(66, 2.27),
      row(93, 10.06),
      row(102, 14.79),
      row(169, 9.92),
      row(212, 1.75),
      row(222, 15.46),
    ]);
    // The lane DRAWS the last two rows as one span (an 8 s hole, inside the 15 s drawing
    // tolerance) — but 20:47:30 sits in its second run, 20:47:24 → 20:47:39.46.
    const island = spans[spans.length - 1];
    expect(island.startMs).toBe(base + 212_000);
    expect(island.runs).toHaveLength(2);
    const run = island.runs![1];
    expect(run.startMs).toBe(base + 222_000);

    footage.mockResolvedValueOnce(spans);
    renderPlayer(FRONT);
    await laneLanded();
    scrubBack(1);

    await recordedVideo();
    // Signed for the RUN: the playlist's zero is 20:47:24, so the 6 s wall-clock seek shows
    // 20:47:30. Signed for the span, Frigate would have concatenated the 1.75 s scrap and the
    // 15.46 s segment back-to-back and the same seek would have shown a frame ~8 s late.
    expect(signed).toHaveBeenCalledWith("front", run.startMs, run.endMs);
    expect(signed).not.toHaveBeenCalledWith("front", island.startMs, island.endMs);
  });
});

/**
 * A battery camera records DURING the session — every PIR wake is new footage — so its lane
 * cannot be fetched once per open like a 24/7 camera's. HA flips the Frigate camera entity's
 * state around each wake (`idle` → `streaming` → `idle`); each transition is exactly one refetch,
 * and the spans already on screen stay until the new ones land.
 */
describe("CameraPlayer — battery camera lane refresh", () => {
  const now = NOON_ISH.getTime();
  const island: FootageSpan[] = [
    { startMs: now - 50 * 60_000, endMs: now - 40 * 60_000, playable: true },
  ];

  /** HA publishing the camera entity with a new state (or only new attributes). */
  function publish(camera: LogicalCamera, state: string, attributes: Record<string, unknown> = {}) {
    act(() => {
      useEntityStore.getState().upsertEntities([
        { ...camera.liveEntity, state, attributes: { ...camera.liveEntity.attributes, ...attributes } },
      ]);
    });
  }

  async function settle() {
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
  }

  it("refetches the lane exactly once per entity state transition, keeping the old spans meanwhile", async () => {
    let releaseRefresh: (spans: FootageSpan[]) => void = () => undefined;
    footage
      .mockResolvedValueOnce(island)
      .mockImplementationOnce(() => new Promise<FootageSpan[]>((resolve) => (releaseRefresh = resolve)))
      .mockResolvedValueOnce(island);
    publish(FRONT, "idle");
    renderPlayer(FRONT);
    await waitFor(() => expect(footage).toHaveBeenCalledTimes(1));
    await settle();
    // The lane is drawn (" · 24/7" is the strip's own tell that footage spans exist).
    expect(screen.getByText(/24\/7/)).toBeInTheDocument();

    // The PIR woke it: one transition, one refetch…
    publish(FRONT, "streaming");
    await waitFor(() => expect(footage).toHaveBeenCalledTimes(2));
    // …and while that refetch is in flight the lane must not flash to empty.
    expect(screen.getByText(/24\/7/)).toBeInTheDocument();
    // Attribute-only churn (battery %, snapshot republish) is not a transition.
    publish(FRONT, "streaming", { battery_level: 87 });
    await settle();
    expect(footage).toHaveBeenCalledTimes(2);

    act(() => releaseRefresh(island));
    await settle();
    // Back to sleep: the third and last refetch, which is when the new footage is on disk.
    publish(FRONT, "idle");
    await waitFor(() => expect(footage).toHaveBeenCalledTimes(3));
    await settle();
    expect(screen.getByText(/24\/7/)).toBeInTheDocument();
    expect(footage).toHaveBeenCalledTimes(3);
  });

  it("never refetches for a camera that does not sleep", async () => {
    footage.mockResolvedValueOnce(island);
    publish(BEDROOM, "idle");
    renderPlayer(BEDROOM);
    await waitFor(() => expect(footage).toHaveBeenCalledTimes(1));

    // A 24/7 camera's state says nothing about new footage — it is all new footage.
    publish(BEDROOM, "streaming");
    await settle();
    publish(BEDROOM, "idle");
    await settle();
    expect(footage).toHaveBeenCalledTimes(1);
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

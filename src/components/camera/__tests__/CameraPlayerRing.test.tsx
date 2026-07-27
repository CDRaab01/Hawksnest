import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { CameraPlayer } from "../CameraPlayer";
import { useEntityStore } from "../../../store/entityStore";
import * as connection from "../../../store/connection";
import type { HassEntity } from "../../../lib/ha";
import type { LogicalCamera } from "../../../lib/cameraModel";

// Isolate the connection seam so the ring stream resolution is fully scriptable
// (the demo fixture source can't fail `camera/stream`). Everything else keeps
// its real no-source behavior (LivePlayer etc. degrade gracefully).
vi.mock("../../../store/connection", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../../store/connection")>()),
  fetchCameraEvents: vi.fn(async () => []),
  recordingUrlAt: vi.fn(() => null),
  streamUrl: vi.fn(async () => null),
  callService: vi.fn(async () => {}),
}));

const streamUrlMock = vi.mocked(connection.streamUrl);
const callServiceMock = vi.mocked(connection.callService);

const NOW = Date.now();
const iso = (msAgo: number) => new Date(NOW - msAgo).toISOString();

// Options carry parseable timestamps so ringEventsFromSelect plots real times.
const OPTIONS = [`Motion ${iso(3600_000)}`, `Motion ${iso(7200_000)}`, `Ding ${iso(10_800_000)}`];

const entity: HassEntity = {
  entity_id: "camera.gate_live",
  state: "idle",
  attributes: { entity_picture: "/api/camera_proxy/camera.gate_live?token=x" },
};

const GATE: LogicalCamera = {
  id: "camera.gate",
  name: "Gate",
  liveEntity: entity,
  snapshotEntity: entity,
  eventStreamId: "camera.gate_event",
  eventSelectId: "select.gate_event_select",
  dingId: null,
  motionId: null,
  sirenSwitchId: null,
};

// What the `camera.gate_event` stream resolves to, per test. Routed by entity id
// because the mounted LivePlayer also asks streamUrl for the LIVE feed — a flat
// mockResolvedValueOnce queue would be consumed by that call first.
let eventStream: () => Promise<string | null>;
const eventStreamCalls = () =>
  streamUrlMock.mock.calls.filter(([id]) => id === "camera.gate_event").length;

beforeEach(() => {
  // Full reset: clearAllMocks keeps implementations, and test 1 installs a
  // never-resolving eventStream that must not leak forward.
  eventStream = async () => null;
  streamUrlMock.mockReset();
  streamUrlMock.mockImplementation((id) =>
    id === "camera.gate_event" ? eventStream() : Promise.resolve(null),
  );
  callServiceMock.mockReset();
  callServiceMock.mockResolvedValue(undefined);
  useEntityStore.setState({
    entities: {
      "select.gate_event_select": {
        entity_id: "select.gate_event_select",
        state: OPTIONS[0],
        attributes: { options: OPTIONS },
      } as HassEntity,
    },
    areas: {},
    status: "connected",
  });
});

function renderPlayer() {
  render(
    <MemoryRouter>
      <CameraPlayer camera={GATE} cameras={[GATE]} onSelectCamera={vi.fn()} />
    </MemoryRouter>,
  );
}

async function seekToFirstClip(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText("3 moments");
  const markers = screen.getAllByRole("button", { name: /at / });
  await user.click(markers[0]);
}

/** No ring-timeline service by default: these tests cover the ring-mqtt selector fallback. */
beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({ ok: false, status: 502, json: async () => ({}) })),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("CameraPlayer (ring recorded playback)", () => {
  it("shows Loading while the stream resolves, then plays the clip", async () => {
    const user = userEvent.setup();
    let resolveUrl!: (url: string | null) => void;
    eventStream = () => new Promise<string | null>((r) => (resolveUrl = r));
    renderPlayer();
    await seekToFirstClip(user);

    expect(await screen.findByText("Loading recording…")).toBeInTheDocument();
    expect(callServiceMock).toHaveBeenCalledWith(
      "select",
      "select_option",
      expect.objectContaining({ entity_id: "select.gate_event_select" }),
    );

    // A plain mp4 URL keeps jsdom off the hls.js path.
    await act(async () => resolveUrl("/demo/camera-loop.mp4"));
    expect(await screen.findByLabelText("Camera footage")).toBeInTheDocument();
    expect(screen.queryByText("Loading recording…")).toBeNull();
  });

  it("a null stream URL becomes an honest failure with a working Retry — never a stuck loader", async () => {
    const user = userEvent.setup();
    // First resolution steps down to null (e.g. HA's 15s timeout), the retry succeeds.
    const results = [null, "/demo/camera-loop.mp4"];
    eventStream = async () => results.shift() ?? null;
    renderPlayer();
    await seekToFirstClip(user);

    expect(
      await screen.findByText("Couldn't load this recording"),
    ).toBeInTheDocument();
    expect(screen.queryByText("Loading recording…")).toBeNull();

    // Retry re-resolves; this time HA produces a stream.
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByLabelText("Camera footage")).toBeInTheDocument();
    expect(eventStreamCalls()).toBe(2);
  });

  it("a rejected select_option still tries the event stream (best-effort select)", async () => {
    const user = userEvent.setup();
    callServiceMock.mockRejectedValueOnce(new Error("option rotated out"));
    eventStream = async () => "/demo/camera-loop.mp4";
    renderPlayer();
    await seekToFirstClip(user);

    expect(await screen.findByLabelText("Camera footage")).toBeInTheDocument();
  });

  it("a throwing streamUrl fails honestly too", async () => {
    const user = userEvent.setup();
    eventStream = () => Promise.reject(new Error("socket died"));
    renderPlayer();
    await seekToFirstClip(user);

    expect(
      await screen.findByText("Couldn't load this recording"),
    ).toBeInTheDocument();
  });

  // ring-mqtt 5.x: no `camera.<base>_event` entity at all — the selected event's
  // recording arrives as the selector's `recordingUrl` attribute. Before this was
  // handled, every recorded clip sat on "Loading recording…" forever.
  it("plays the recording ring-mqtt publishes on the selector (no _event entity)", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <CameraPlayer
          camera={{ ...GATE, eventStreamId: null }}
          cameras={[GATE]}
          onSelectCamera={vi.fn()}
        />
      </MemoryRouter>,
    );
    await seekToFirstClip(user);

    expect(await screen.findByText("Loading recording…")).toBeInTheDocument();
    await vi.waitFor(() =>
      expect(callServiceMock).toHaveBeenCalledWith(
        "select",
        "select_option",
        expect.objectContaining({ entity_id: "select.gate_event_select" }),
      ),
    );

    // ring-mqtt answers: selection lands together with that event's recording.
    const option = callServiceMock.mock.calls[0][2]?.option as string;
    act(() =>
      useEntityStore.setState({
        entities: {
          "select.gate_event_select": {
            entity_id: "select.gate_event_select",
            state: option,
            // A plain mp4 URL keeps jsdom off the hls.js path.
            attributes: { options: OPTIONS, recordingUrl: "https://ring.test/clip.mp4" },
          } as HassEntity,
        },
      }),
    );

    expect(await screen.findByLabelText("Camera footage")).toBeInTheDocument();
    expect(screen.queryByText("Loading recording…")).toBeNull();
    // No `_event` camera to ask HA about — the URL came off the selector.
    expect(eventStreamCalls()).toBe(0);
  });
});

describe("CameraPlayer (ring-timeline service)", () => {
  const DEVICE = { id: 42, name: "Gate", slug: "gate" };
  const RECORDING = {
    id: "7667005335319651402",
    startMs: NOW - 3600_000,
    endMs: NOW - 3600_000 + 37_000,
    durationSec: 37,
    kind: "motion",
    person: true,
    url: "https://ring.test/clip.mp4",
    urlExpiresAtMs: NOW + 900_000,
    thumbnailUrl: "https://ring.test/thumb.jpg",
  };

  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string) => ({
        ok: true,
        status: 200,
        json: async () =>
          String(input).includes("/cameras") ? [DEVICE] : { events: [RECORDING], truncated: false },
      })),
    );
  });

  // The whole point of the service: the event arrives WITH its playable URL, so scrubbing to a
  // recording plays it � no select_option, no waiting on ring-mqtt, no "Loading recording�".
  it("plays a recording immediately, without touching the ring-mqtt selector", async () => {
    const user = userEvent.setup();
    renderPlayer();

    await screen.findByText("1 moments");
    await user.click(screen.getAllByRole("button", { name: /at / })[0]);

    expect(await screen.findByLabelText("Camera footage")).toBeInTheDocument();
    expect(screen.queryByText("Loading recording�")).toBeNull();
    expect(callServiceMock).not.toHaveBeenCalled();
    expect(eventStreamCalls()).toBe(0);
  });
});

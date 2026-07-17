import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { CameraPlayer } from "../CameraPlayer";
import { startConnection } from "../../../store/connection";
import { useEntityStore } from "../../../store/entityStore";
import type { HassEntity } from "../../../lib/ha";
import type { LogicalCamera } from "../../../lib/cameraModel";

const lc = (id: string, name: string): LogicalCamera => {
  const entity: HassEntity = {
    entity_id: id,
    state: "idle",
    attributes: { entity_picture: `/api/camera_proxy/${id}?token=x` },
  };
  return {
    id,
    name,
    liveEntity: entity,
    snapshotEntity: entity,
    eventStreamId: null,
    eventSelectId: null,
    dingId: null,
    motionId: null,
    sirenSwitchId: null,
  };
};

const FRONT = lc("camera.front_door", "Front Door");
const BACK = lc("camera.backyard", "Backyard");

beforeEach(() => {
  useEntityStore.setState({ entities: {}, areas: {}, status: "connecting" });
  // No saved credentials in the test env → the demo fixture source is selected,
  // which serves the bundled clip + synthesized camera events.
  startConnection();
});

function renderPlayer(onSelect = vi.fn()) {
  render(
    <MemoryRouter>
      <CameraPlayer camera={FRONT} cameras={[FRONT, BACK]} onSelectCamera={onSelect} />
    </MemoryRouter>,
  );
  return onSelect;
}

describe("CameraPlayer (demo data)", () => {
  it("opens live, with the switcher, a populated timeline, and transport", async () => {
    renderPlayer();

    // In-player camera switcher shows the resolved current camera name.
    expect(screen.getByRole("button", { name: /Front Door/ })).toBeInTheDocument();
    // Live by default — the demo clip plays in a <video> (after the async
    // stream URL resolves; until then a snapshot placeholder holds the frame).
    expect(await screen.findByLabelText("Camera footage")).toBeInTheDocument();
    // The 24h scrubber renders and populates with synthesized events, under the Ring-style
    // day header.
    expect(screen.getByRole("slider", { name: "Recording timeline" })).toBeInTheDocument();
    expect(screen.getByText("TODAY")).toBeInTheDocument();
    expect(await screen.findByText(/\d+ moments/)).toBeInTheDocument();
    expect(Number((await screen.findByText(/\d+ moments/)).textContent!.split(" ")[0]))
      .toBeGreaterThan(0);
    // Transport controls present.
    expect(screen.getByRole("button", { name: "Previous moment" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next moment" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Go live" })).toBeInTheDocument();
  });

  it("seeking to an event enters recorded mode; Live snaps back", async () => {
    const user = userEvent.setup();
    renderPlayer();
    await screen.findByText(/\d+ moments/);

    // Click the first event marker on the timeline → recorded playback.
    const markers = screen.getAllByRole("button", { name: /at / });
    await user.click(markers[0]);
    expect(await screen.findByText("Recorded")).toBeInTheDocument();
    expect(screen.getByLabelText("Camera footage")).toBeInTheDocument();

    // Snap back to live.
    await user.click(screen.getByRole("button", { name: "Go live" }));
    expect(screen.queryByText("Recorded")).toBeNull();
    expect(screen.getAllByText("Live").length).toBeGreaterThan(0);
  });

  it("the switcher selects another camera", async () => {
    const user = userEvent.setup();
    const onSelect = renderPlayer();

    await user.click(screen.getByRole("button", { name: /Front Door/ }));
    // The option's clickable is the inner button; click it by its unique name.
    await user.click(screen.getByRole("button", { name: "Backyard" }));

    expect(onSelect).toHaveBeenCalledWith(BACK);
  });
});

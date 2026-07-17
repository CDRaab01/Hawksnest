import { describe, it, expect, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { CameraTile } from "../CameraTile";
import { useEntityStore } from "../../store/entityStore";
import { useCameraOverlay, viewTransitionNameFor } from "../../store/cameraOverlay";
import type { HassEntity } from "../../lib/ha";

beforeEach(() => {
  useEntityStore.setState({ entities: {}, areas: {}, status: "demo", baseUrl: "" });
  useCameraOverlay.setState({ openId: null });
});

function cam(picture: string, lastChanged?: string): HassEntity {
  return {
    entity_id: "camera.back",
    state: "idle",
    attributes: { friendly_name: "Back", entity_picture: picture },
    last_changed: lastChanged,
  };
}

/** The hidden preloader <img> (alt="" / .hidden); promoting it is what swaps the frame. */
function preloader(container: HTMLElement): HTMLImageElement | null {
  return container.querySelector("img.hidden");
}

describe("CameraTile", () => {
  it("holds the last decoded frame across a refresh instead of blanking", () => {
    const { container, rerender } = render(
      <CameraTile entity={cam("/a.svg")} overrides={{}} name="Back" />,
    );
    // Promote the first frame.
    fireEvent.load(preloader(container)!);
    expect(screen.getByAltText("Back live snapshot")).toHaveAttribute(
      "src",
      expect.stringContaining("a.svg"),
    );

    // A new frame URL arrives (next bucket / new entity_picture) but hasn't loaded yet:
    // the visible tile must still show the OLD frame, not go black.
    rerender(<CameraTile entity={cam("/b.svg")} overrides={{}} name="Back" />);
    expect(screen.getByAltText("Back live snapshot")).toHaveAttribute(
      "src",
      expect.stringContaining("a.svg"),
    );

    // Once it decodes, swap to the new frame.
    fireEvent.load(preloader(container)!);
    expect(screen.getByAltText("Back live snapshot")).toHaveAttribute(
      "src",
      expect.stringContaining("b.svg"),
    );
  });

  it("keeps the last frame when a later refresh errors (no 'No signal' flash)", () => {
    const { container, rerender } = render(
      <CameraTile entity={cam("/a.svg")} overrides={{}} name="Back" />,
    );
    fireEvent.load(preloader(container)!);

    rerender(<CameraTile entity={cam("/b.svg")} overrides={{}} name="Back" />);
    fireEvent.error(preloader(container)!);

    // Still showing the good frame; the failure placeholder must not appear.
    expect(screen.getByAltText("Back live snapshot")).toHaveAttribute(
      "src",
      expect.stringContaining("a.svg"),
    );
    expect(screen.queryByText("No signal")).toBeNull();
  });

  it("shows the skeleton shimmer until the first frame decodes, then reveals it", () => {
    const { container } = render(
      <CameraTile entity={cam("/a.svg")} overrides={{}} name="Back" />,
    );
    // First paint: shimmer up, the visible frame held transparent beneath it.
    expect(screen.getByTestId("skeleton")).toBeInTheDocument();
    expect(screen.getByAltText("Back live snapshot")).toHaveClass("opacity-0");

    fireEvent.load(preloader(container)!);
    // Frame decoded: skeleton gone for good, frame revealed.
    expect(screen.queryByTestId("skeleton")).toBeNull();
    expect(screen.getByAltText("Back live snapshot")).toHaveClass("opacity-100");
  });

  it("shows the offline placeholder — not a skeleton — when the camera isn't live", () => {
    render(
      <CameraTile
        entity={{
          entity_id: "camera.back",
          state: "unavailable",
          attributes: { friendly_name: "Back" },
        }}
        overrides={{}}
        name="Back"
      />,
    );
    expect(screen.queryByTestId("skeleton")).toBeNull();
    expect(screen.getByText("Offline")).toBeInTheDocument();
  });

  it("names the tile for the view transition — and yields the name while open", () => {
    const { container } = render(
      <CameraTile
        entity={cam("/a.svg")}
        overrides={{}}
        name="Back"
        transitionId="camera.back"
      />,
    );
    // Named while closed (the tile is the transition source).
    expect(
      container.querySelector(`[data-transition="${viewTransitionNameFor("camera.back")}"]`),
    ).not.toBeNull();

    // Open in the player: the PLAYER owns the name — the tile must release it
    // (a view-transition-name must be unique on screen).
    act(() => {
      useCameraOverlay.setState({ openId: "camera.back" });
    });
    expect(container.querySelector("[data-transition]")).toBeNull();
  });

  it("pulses the ding ring only while the doorbell is ringing", () => {
    const { rerender } = render(
      <CameraTile entity={cam("/a.svg")} overrides={{}} name="Back" ringing />,
    );
    expect(screen.getByTestId("ding-ring")).toBeInTheDocument();

    rerender(<CameraTile entity={cam("/a.svg")} overrides={{}} name="Back" ringing={false} />);
    expect(screen.queryByTestId("ding-ring")).toBeNull();
  });

  it("renders a sane age from an epoch-seconds last_changed (not a 1970 badge)", () => {
    const tenMinAgoSec = Math.floor(Date.now() / 1000) - 600;
    render(
      <CameraTile entity={cam("/a.svg", String(tenMinAgoSec))} overrides={{}} name="Back" />,
    );
    expect(screen.getByText("10m ago")).toBeInTheDocument();
  });

  it("badge prefers snapshot freshness (last_updated) over hours-stale last_changed", () => {
    // The "15h ago" bug: a camera's state rarely transitions, so last_changed is
    // ancient even while ring-mqtt republishes the snapshot (bumping last_updated).
    const entity: HassEntity = {
      entity_id: "camera.back",
      state: "idle",
      attributes: { friendly_name: "Back", entity_picture: "/a.svg" },
      last_changed: String(Math.floor(Date.now() / 1000) - 15 * 3600),
      last_updated: String(Math.floor(Date.now() / 1000) - 30),
    };
    render(<CameraTile entity={entity} overrides={{}} name="Back" />);
    expect(screen.getByText("30s ago")).toBeInTheDocument();
    expect(screen.queryByText("15h ago")).toBeNull();
  });
});

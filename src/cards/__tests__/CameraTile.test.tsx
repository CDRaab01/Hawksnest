import { describe, it, expect, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CameraTile } from "../CameraTile";
import { useEntityStore } from "../../store/entityStore";
import type { HassEntity } from "../../lib/ha";

beforeEach(() => {
  useEntityStore.setState({ entities: {}, areas: {}, status: "demo", baseUrl: "" });
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

  it("renders a sane age from an epoch-seconds last_changed (not a 1970 badge)", () => {
    const tenMinAgoSec = Math.floor(Date.now() / 1000) - 600;
    render(
      <CameraTile entity={cam("/a.svg", String(tenMinAgoSec))} overrides={{}} name="Back" />,
    );
    expect(screen.getByText("10m ago")).toBeInTheDocument();
  });
});

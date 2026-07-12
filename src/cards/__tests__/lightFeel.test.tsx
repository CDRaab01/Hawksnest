import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { LightCard } from "../LightCard";
import { callService } from "../../store/connection";
import type { HassEntity } from "../../lib/ha";

vi.mock("../../store/connection", () => ({
  callService: vi.fn(() => Promise.resolve()),
}));
const mockCall = vi.mocked(callService);

const light = (state: string, brightness?: number): HassEntity => ({
  entity_id: "light.porch",
  state,
  attributes: { friendly_name: "Porch", ...(brightness !== undefined ? { brightness } : {}) },
});

beforeEach(() => {
  mockCall.mockClear();
  mockCall.mockImplementation(() => Promise.resolve());
});

describe("LightCard feel", () => {
  it("toggles optimistically: the switch flips on tap, before any echo", () => {
    render(<LightCard entity={light("off")} overrides={{}} />);
    const toggle = screen.getByRole("switch", { name: "Toggle Porch" });
    expect(toggle).toHaveAttribute("aria-checked", "false");

    fireEvent.click(toggle);
    // Store still says off — but the thumb has already followed the finger.
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(mockCall).toHaveBeenCalledWith("light", "turn_on", {
      entity_id: "light.porch",
    });
  });

  it("snaps back with a message when the call fails", async () => {
    mockCall.mockImplementation(() => Promise.reject(new Error("down")));
    render(<LightCard entity={light("off")} overrides={{}} />);
    const toggle = screen.getByRole("switch", { name: "Toggle Porch" });

    await act(async () => {
      fireEvent.click(toggle);
    });

    expect(toggle).toHaveAttribute("aria-checked", "false"); // snapped back
    expect(screen.getByText("Couldn't reach the light.")).toBeInTheDocument();
  });

  it("commits brightness once per gesture (on release), not per drag tick", () => {
    render(<LightCard entity={light("on", 128)} overrides={{}} />);
    const slider = screen.getByRole("slider", { name: "Porch brightness" });

    // Drag: several change events, no service calls yet.
    fireEvent.change(slider, { target: { value: "40" } });
    fireEvent.change(slider, { target: { value: "55" } });
    fireEvent.change(slider, { target: { value: "70" } });
    expect(mockCall).not.toHaveBeenCalled();
    // The mono readout live-tracks the drag.
    expect(screen.getByText("70")).toBeInTheDocument();

    // Release: exactly one call, with the final value.
    fireEvent.pointerUp(slider);
    expect(mockCall).toHaveBeenCalledTimes(1);
    expect(mockCall).toHaveBeenCalledWith("light", "turn_on", {
      entity_id: "light.porch",
      brightness_pct: 70,
    });
  });
});

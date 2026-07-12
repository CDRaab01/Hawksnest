import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { SecurityStatusBar } from "../SecurityStatusBar";
import { Skeleton } from "../Skeleton";
import { useEntityStore } from "../../store/entityStore";

vi.mock("../../store/connection", () => ({
  callService: vi.fn(() => Promise.resolve()),
}));

beforeEach(() => {
  useEntityStore.setState({
    status: "connected",
    areas: {},
    entities: {
      "alarm_control_panel.home": {
        entity_id: "alarm_control_panel.home",
        state: "disarmed",
        attributes: { friendly_name: "Alarm" },
      },
    },
  });
});

describe("SecurityStatusBar — arm fill-sweep", () => {
  it("sweeps the channel fill into the active mode's disc only", () => {
    render(<SecurityStatusBar />);
    const off = screen.getByRole("button", { name: "Off" });
    const away = screen.getByRole("button", { name: "Away" });

    // Active (disarmed) disc: fill layer scaled up. Inactive: scaled to zero.
    expect(off.querySelector(".scale-100")).not.toBeNull();
    expect(away.querySelector(".scale-100")).toBeNull();
    expect(away.querySelector(".scale-0")).not.toBeNull();
  });

  it("stays non-optimistic: a tap spins, the fill only sweeps on HA's echo", () => {
    render(<SecurityStatusBar />);
    const away = screen.getByRole("button", { name: "Away" });

    fireEvent.click(away);
    // Pending: busy spinner up, but the fill has NOT swept (store still disarmed).
    expect(away).toHaveAttribute("aria-busy", "true");
    expect(away.querySelector(".scale-100")).toBeNull();

    // HA's echo lands.
    act(() => {
      useEntityStore.setState({
        entities: {
          "alarm_control_panel.home": {
            entity_id: "alarm_control_panel.home",
            state: "armed_away",
            attributes: { friendly_name: "Alarm" },
          },
        },
      });
    });
    expect(away).toHaveAttribute("aria-pressed", "true");
    expect(away.querySelector(".scale-100")).not.toBeNull();
  });
});

describe("Skeleton", () => {
  it("renders the shimmer surface with an accessible label", () => {
    render(<Skeleton className="h-4" label="Loading history" />);
    const el = screen.getByTestId("skeleton");
    expect(el).toHaveClass("bg-panel-high");
    expect(screen.getByText("Loading history")).toHaveClass("sr-only");
  });
});

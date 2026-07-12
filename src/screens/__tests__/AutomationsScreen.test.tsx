import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

// Stub the source plumbing — we only assert which HA service calls the rows make.
// Returns a promise so the rows' optimistic `.then/.catch` chaining works.
const callService = vi.fn((..._args: unknown[]) => Promise.resolve());
vi.mock("../../store/connection", () => ({
  callService: (...args: unknown[]) => callService(...args),
}));

import { AutomationsScreen } from "../AutomationsScreen";
import { useEntityStore } from "../../store/entityStore";

function seedAutomation(state = "on") {
  useEntityStore.setState({
    entities: {
      "automation.lock_all_doors": {
        entity_id: "automation.lock_all_doors",
        state,
        attributes: { friendly_name: "Lock all doors", id: "111" },
      },
    },
    areas: {},
    status: "connected",
    error: undefined,
  });
}

beforeEach(() => {
  callService.mockClear();
  callService.mockImplementation(() => Promise.resolve());
  useEntityStore.setState({ entities: {}, areas: {}, status: "connected", error: undefined });
});

function renderScreen() {
  return render(
    <MemoryRouter>
      <AutomationsScreen />
    </MemoryRouter>,
  );
}

describe("Automations list", () => {
  it("lists each automation entity with its enabled state", () => {
    seedAutomation("on");
    renderScreen();
    expect(screen.getByText("Lock all doors")).toBeInTheDocument();
    expect(screen.getByText(/Enabled/)).toBeInTheDocument();
  });

  it("shows an empty state when there are no automations", () => {
    renderScreen();
    expect(screen.getByText(/No automations yet/)).toBeInTheDocument();
  });

  it("disables an enabled automation via the automation service", async () => {
    const user = userEvent.setup();
    seedAutomation("on");
    renderScreen();

    await user.click(screen.getByRole("button", { name: "Disable" }));

    expect(callService).toHaveBeenCalledWith("automation", "turn_off", {
      entity_id: "automation.lock_all_doors",
    });
  });

  it("runs an automation on demand and flashes a confirmation", async () => {
    const user = userEvent.setup();
    seedAutomation("on");
    renderScreen();

    await user.click(screen.getByRole("button", { name: "Run now" }));

    expect(callService).toHaveBeenCalledWith("automation", "trigger", {
      entity_id: "automation.lock_all_doors",
    });
    // The invisible action gets visible feedback.
    expect(await screen.findByText("Ran just now")).toBeInTheDocument();
  });

  it("toggles optimistically — the button flips before any echo", async () => {
    const user = userEvent.setup();
    seedAutomation("on");
    renderScreen();
    const btn = screen.getByRole("button", { name: "Disable" });
    expect(btn).toHaveAttribute("aria-pressed", "true");

    await user.click(btn);
    // Store still says "on"; the control already reflects the intent.
    expect(screen.getByRole("button", { name: "Enable" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("surfaces an error and snaps back when the toggle fails", async () => {
    const user = userEvent.setup();
    callService.mockImplementation(() => Promise.reject(new Error("down")));
    seedAutomation("on");
    renderScreen();

    await user.click(screen.getByRole("button", { name: "Disable" }));

    expect(await screen.findByText("Couldn't reach Home Assistant.")).toBeInTheDocument();
    // Snapped back to enabled.
    expect(screen.getByRole("button", { name: "Disable" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });
});

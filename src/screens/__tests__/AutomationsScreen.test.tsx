import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

// Stub the source plumbing — we only assert which HA service calls the rows make.
const callService = vi.fn();
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

  it("runs an automation on demand", async () => {
    const user = userEvent.setup();
    seedAutomation("on");
    renderScreen();

    await user.click(screen.getByRole("button", { name: "Run now" }));

    expect(callService).toHaveBeenCalledWith("automation", "trigger", {
      entity_id: "automation.lock_all_doors",
    });
  });
});

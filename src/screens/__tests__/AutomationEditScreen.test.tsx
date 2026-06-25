import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";

// Mock the editor's persistence so no real Config API call is made.
const saveAutomationConfig = vi.fn().mockResolvedValue(undefined);
const deleteAutomationConfig = vi.fn().mockResolvedValue(undefined);
const getAutomationConfig = vi.fn();
vi.mock("../../store/connection", () => ({
  saveAutomationConfig: (c: unknown) => saveAutomationConfig(c),
  deleteAutomationConfig: (id: string) => deleteAutomationConfig(id),
  getAutomationConfig: (id: string) => getAutomationConfig(id),
}));

import { AutomationEditScreen } from "../AutomationEditScreen";
import { useEntityStore } from "../../store/entityStore";

function seedEntities() {
  useEntityStore.setState({
    entities: {
      "alarm_control_panel.home": {
        entity_id: "alarm_control_panel.home",
        state: "disarmed",
        attributes: { friendly_name: "Alarm" },
      },
      "lock.front_door_lock": {
        entity_id: "lock.front_door_lock",
        state: "locked",
        attributes: { friendly_name: "Front Door" },
      },
      "lock.back_door_lock": {
        entity_id: "lock.back_door_lock",
        state: "locked",
        attributes: { friendly_name: "Back Door" },
      },
    },
    areas: {
      "alarm_control_panel.home": "Security",
      "lock.front_door_lock": "Front Door",
      "lock.back_door_lock": "Back Door",
    },
    status: "connected",
    error: undefined,
  });
}

beforeEach(() => {
  saveAutomationConfig.mockClear();
  deleteAutomationConfig.mockClear();
  getAutomationConfig.mockReset();
  seedEntities();
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/automations/:id" element={<AutomationEditScreen />} />
        <Route path="/automations" element={<div>AUTOMATIONS LIST</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("Automation editor — create", () => {
  it("builds an HA automation from the form and saves it", async () => {
    const user = userEvent.setup();
    renderAt("/automations/new");

    await user.type(screen.getByLabelText("Name"), "Lock all doors when armed");
    await user.selectOptions(
      screen.getByLabelText("Trigger device"),
      "alarm_control_panel.home",
    );
    await user.selectOptions(screen.getByLabelText("Trigger state"), "armed_home");

    // Default action is a lock action; target both deadbolts.
    await user.click(
      screen.getByLabelText("Front Door target for action 1"),
    );
    await user.click(screen.getByLabelText("Back Door target for action 1"));

    await user.click(screen.getByRole("button", { name: "Create automation" }));

    expect(saveAutomationConfig).toHaveBeenCalledTimes(1);
    const config = saveAutomationConfig.mock.calls[0][0];
    expect(config.alias).toBe("Lock all doors when armed");
    expect(config.trigger).toEqual([
      { platform: "state", entity_id: "alarm_control_panel.home", to: "armed_home" },
    ]);
    expect(config.action).toEqual([
      {
        service: "lock.lock",
        target: { entity_id: ["lock.front_door_lock", "lock.back_door_lock"] },
      },
    ]);
    // Navigated back to the list on success.
    expect(await screen.findByText("AUTOMATIONS LIST")).toBeInTheDocument();
  });

  it("blocks saving until a trigger and a target are set", async () => {
    const user = userEvent.setup();
    renderAt("/automations/new");

    await user.type(screen.getByLabelText("Name"), "Incomplete");
    await user.click(screen.getByRole("button", { name: "Create automation" }));

    expect(saveAutomationConfig).not.toHaveBeenCalled();
    expect(
      screen.getByText(/Pick a trigger device and the state/),
    ).toBeInTheDocument();
  });
});

describe("Automation editor — existing", () => {
  it("prefills the form from a parseable config", async () => {
    getAutomationConfig.mockResolvedValue({
      id: "111",
      alias: "Hall light on motion",
      trigger: [{ platform: "state", entity_id: "binary_sensor.hall", to: "on" }],
      action: [{ service: "lock.lock", target: { entity_id: ["lock.front_door_lock"] } }],
    });

    renderAt("/automations/111");

    expect(await screen.findByDisplayValue("Hall light on motion")).toBeInTheDocument();
    expect(getAutomationConfig).toHaveBeenCalledWith("111");
  });

  it("shows the edit-in-HA fallback for an unsupported config", async () => {
    getAutomationConfig.mockResolvedValue({
      id: "222",
      alias: "Complex sunrise scene",
      trigger: [{ platform: "sun", event: "sunset" }],
      action: [{ service: "scene.turn_on", target: { entity_id: "scene.x" } }],
    });

    renderAt("/automations/222");

    expect(
      await screen.findByText(/features Hawksnest can't edit yet/),
    ).toBeInTheDocument();
  });
});

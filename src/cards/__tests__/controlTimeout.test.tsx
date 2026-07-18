import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act, fireEvent } from "@testing-library/react";
import { LockCard } from "../LockCard";
import { AlarmCard } from "../AlarmCard";
import { callService } from "../../store/connection";
import { useEntityStore } from "../../store/entityStore";
import type { HassEntity } from "../../lib/ha";

// The control cards call HA through this module; mock it so the call is
// "accepted" (resolves) but no state echo ever follows — the no-response case
// the timeout guards against.
vi.mock("../../store/connection", () => ({
  callService: vi.fn(() => Promise.resolve()),
}));
const mockCall = vi.mocked(callService);

const lock = (state: string): HassEntity => ({
  entity_id: "lock.front_door",
  state,
  attributes: { friendly_name: "Front Door" },
});

const alarm = (state: string): HassEntity => ({
  entity_id: "alarm_control_panel.home",
  state,
  attributes: { friendly_name: "Alarm" },
});

beforeEach(() => {
  vi.useFakeTimers();
  mockCall.mockClear();
  // The security cards render an explicit "Unknown — offline" while disconnected;
  // these timeout flows are about a LIVE connection whose device never echoes.
  useEntityStore.setState({ status: "connected" });
});
afterEach(() => {
  vi.useRealTimers();
});

describe("control no-response timeouts", () => {
  it("LockCard stops spinning and reports when the lock never echoes", () => {
    render(<LockCard entity={lock("locked")} overrides={{}} />);

    // Keyboard commit on the slide thumb (the drag's accessible equivalent).
    fireEvent.keyDown(screen.getByRole("button", { name: "Slide to unlock" }), {
      key: "Enter",
    });
    expect(mockCall).toHaveBeenCalledWith("lock", "unlock", { entity_id: "lock.front_door" });
    // Pending shows on the status line AND the slide track.
    expect(screen.getAllByText("Unlocking…").length).toBeGreaterThan(0);

    act(() => {
      vi.advanceTimersByTime(45_000);
    });

    expect(screen.getByText("The lock didn't respond.")).toBeInTheDocument();
    expect(screen.queryByText("Unlocking…")).toBeNull(); // spinner cleared
  });

  it("AlarmCard stops spinning and reports when the panel never echoes", () => {
    render(<AlarmCard entity={alarm("disarmed")} overrides={{}} />);

    fireEvent.click(screen.getByRole("button", { name: "Away" }));
    expect(mockCall).toHaveBeenCalledWith("alarm_control_panel", "alarm_arm_away", {
      entity_id: "alarm_control_panel.home",
    });

    act(() => {
      vi.advanceTimersByTime(90_000);
    });

    expect(screen.getByText("The alarm panel didn't respond.")).toBeInTheDocument();
  });
});

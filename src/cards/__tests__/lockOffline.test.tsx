import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { LockCard } from "../LockCard";
import { AlarmCard } from "../AlarmCard";
import { useEntityStore } from "../../store/entityStore";
import type { HassEntity } from "../../lib/ha";

vi.mock("../../store/connection", () => ({
  callService: vi.fn(() => Promise.resolve()),
}));

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
  useEntityStore.setState({
    entities: {},
    areas: {},
    status: "connected",
    error: undefined,
    lastConnectedAt: undefined,
    staleSince: undefined,
  });
});

/**
 * The security invariant, pinned at the card level: while the HA socket is down a lock/alarm
 * card must read an explicit "Unknown — offline" with its control disabled — never the
 * last-known "Locked"/"Armed" (even though the store also masks the states, the cards must not
 * depend on being handed a fresh entity object).
 */
describe("LockCard while disconnected", () => {
  it("reads Unknown — offline and disables the slide during a reconnect", () => {
    useEntityStore.setState({ status: "connecting" });
    render(<LockCard entity={lock("locked")} overrides={{}} />);

    expect(screen.getByText("Unknown — offline")).toBeInTheDocument();
    expect(screen.queryByText("Locked")).toBeNull();
    expect(screen.getByRole("button", { name: /Slide to/ })).toBeDisabled();
  });

  it("reads Unknown — offline on a terminal error too", () => {
    useEntityStore.setState({ status: "error", error: "Invalid access token." });
    render(<LockCard entity={lock("unlocked")} overrides={{}} />);
    expect(screen.getByText("Unknown — offline")).toBeInTheDocument();
    expect(screen.queryByText("Unlocked")).toBeNull();
  });

  it("renders normally when live (and in demo)", () => {
    render(<LockCard entity={lock("locked")} overrides={{}} />);
    expect(screen.getByText("Locked")).toBeInTheDocument();
    expect(screen.queryByText("Unknown — offline")).toBeNull();
  });
});

describe("AlarmCard while disconnected", () => {
  it("reads Unknown — offline, drops the active mode, and disables the segments", () => {
    useEntityStore.setState({ status: "connecting" });
    render(<AlarmCard entity={alarm("armed_away")} overrides={{}} />);

    expect(screen.getByText("Unknown — offline")).toBeInTheDocument();
    expect(screen.queryByText("Armed — Away")).toBeNull();
    for (const label of ["Off", "Home", "Away"]) {
      expect(screen.getByRole("button", { name: label })).toBeDisabled();
    }
  });

  it("renders the real mode when live", () => {
    render(<AlarmCard entity={alarm("armed_away")} overrides={{}} />);
    expect(screen.getByText("Armed — Away")).toBeInTheDocument();
  });
});

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { DashboardScreen } from "../DashboardScreen";
import { useEntityStore } from "../../store/entityStore";
import { GRACE_WINDOW_MS } from "../../lib/offline";
import type { HassEntity } from "../../lib/ha";

vi.mock("../../store/connection", () => ({
  callService: vi.fn(() => Promise.resolve()),
  retryConnection: vi.fn(),
}));

const entities: Record<string, HassEntity> = {
  "alarm_control_panel.home": {
    entity_id: "alarm_control_panel.home",
    state: "unavailable", // already masked by the store at drop time
    attributes: { friendly_name: "Alarm" },
  },
  "light.porch": {
    entity_id: "light.porch",
    state: "on",
    attributes: { friendly_name: "Porch" },
  },
};

function mount() {
  return render(
    <MemoryRouter>
      <DashboardScreen />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 401 })));
  useEntityStore.setState({
    entities,
    areas: {},
    status: "connected",
    error: undefined,
    baseUrl: "",
    lastConnectedAt: undefined,
    staleSince: undefined,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** The dashboard's live → grace → offline ladder (`lib/offline.ts`). */
describe("DashboardScreen offline phases", () => {
  it("renders normally while live — no banner, no offline state", () => {
    mount();
    expect(screen.queryByTestId("reconnect-banner")).toBeNull();
    expect(screen.queryByTestId("offline-state")).toBeNull();
  });

  it("keeps dimmed content under a reconnect banner during the grace window", () => {
    useEntityStore.setState({
      status: "connecting",
      staleSince: Date.now() - 5_000,
      lastConnectedAt: Date.now() - 5_000,
    });
    mount();
    expect(screen.getByTestId("reconnect-banner")).toHaveTextContent(/^Reconnecting — as of /);
    expect(screen.queryByTestId("offline-state")).toBeNull();
    // Content stays visible but inert (dimmed + pointer-events disabled).
    const dimmed = document.querySelector("[aria-disabled='true']");
    expect(dimmed).not.toBeNull();
    expect(dimmed!.className).toContain("pointer-events-none");
    // The masked alarm shows unknown, never a stale mode.
    expect(screen.getByText("Security state unknown — offline")).toBeInTheDocument();
  });

  it("collapses to the full OfflineState once the grace window expires", () => {
    useEntityStore.setState({
      status: "connecting",
      staleSince: Date.now() - GRACE_WINDOW_MS - 1_000,
    });
    mount();
    expect(screen.getByTestId("offline-state")).toBeInTheDocument();
    expect(screen.queryByTestId("reconnect-banner")).toBeNull();
    // No entity data survives into the offline state.
    expect(screen.queryByText("Porch")).toBeNull();
  });

  it("collapses immediately on a terminal error", () => {
    useEntityStore.setState({ status: "error", error: "Invalid access token." });
    mount();
    expect(screen.getByTestId("offline-state")).toBeInTheDocument();
    expect(screen.getByText("Invalid access token.")).toBeInTheDocument();
  });

  it("keeps the plain connecting UI on a first-ever connect (nothing stale to show)", () => {
    useEntityStore.setState({ status: "connecting", staleSince: undefined });
    mount();
    expect(screen.queryByTestId("reconnect-banner")).toBeNull();
    expect(screen.queryByTestId("offline-state")).toBeNull();
  });
});

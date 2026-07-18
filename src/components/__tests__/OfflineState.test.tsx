import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { OfflineState, ReconnectingBanner } from "../OfflineState";
import { useEntityStore } from "../../store/entityStore";

const retryConnection = vi.fn();
vi.mock("../../store/connection", () => ({
  retryConnection: () => retryConnection(),
}));

beforeEach(() => {
  retryConnection.mockClear();
  useEntityStore.setState({
    entities: {},
    areas: {},
    status: "error",
    error: undefined,
    baseUrl: "",
    lastConnectedAt: undefined,
    staleSince: undefined,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("OfflineState", () => {
  it("shows the honest headline, last-connected readout, and no entity data", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 401 })));
    // Relative to the real clock so the same-day/other-day formatting (pinned in
    // lib/__tests__/offline.test.ts) can't make this flake across a midnight run.
    useEntityStore.setState({ lastConnectedAt: Date.now() - 60_000 });
    render(<OfflineState />);

    expect(screen.getByText("Can't reach Home Assistant")).toBeInTheDocument();
    expect(screen.getByText(/^Last connected /)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("offline-hint")).toBeInTheDocument());
  });

  it("phrases the hint as HA-not-answering when the server responds", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 502 })));
    render(<OfflineState />);
    await waitFor(() =>
      expect(screen.getByTestId("offline-hint")).toHaveTextContent(
        "Home network is reachable — Home Assistant isn't answering.",
      ),
    );
  });

  it("phrases the hint as network-unreachable on a transport failure", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    }));
    render(<OfflineState />);
    await waitFor(() =>
      expect(screen.getByTestId("offline-hint")).toHaveTextContent(
        "Your home network is unreachable — check Tailscale.",
      ),
    );
  });

  it("surfaces the terminal error line and retries via the connection store", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 401 })));
    useEntityStore.setState({ status: "error", error: "Invalid access token." });
    render(<OfflineState />);

    expect(screen.getByText("Invalid access token.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry now" }));
    expect(retryConnection).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByTestId("offline-hint")).toBeInTheDocument());
  });
});

describe("ReconnectingBanner", () => {
  it("carries the as-of readout when known", () => {
    const asOf = new Date(2026, 6, 17, 9, 5).getTime();
    render(<ReconnectingBanner asOf={asOf} />);
    // Same-day renders clock-only; other days carry the date — either way the prefix holds.
    expect(screen.getByTestId("reconnect-banner")).toHaveTextContent(/^Reconnecting — as of /);
  });

  it("degrades to a plain reconnecting line without a timestamp", () => {
    render(<ReconnectingBanner />);
    expect(screen.getByTestId("reconnect-banner")).toHaveTextContent("Reconnecting…");
  });
});

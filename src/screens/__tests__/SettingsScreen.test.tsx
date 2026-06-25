import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

// Mock the source plumbing so "Connect"/"Disconnect" never opens a real socket —
// we only assert that the screen persists credentials and (re)starts the source.
const startConnection = vi.fn();
vi.mock("../../store/connection", () => ({
  startConnection: () => startConnection(),
}));

import { SettingsScreen } from "../SettingsScreen";
import { useEntityStore, type ConnectionStatus } from "../../store/entityStore";
import { loadCredentials, saveCredentials } from "../../store/credentials";

function renderSettings() {
  return render(
    <MemoryRouter>
      <SettingsScreen />
    </MemoryRouter>,
  );
}

function setStatus(status: ConnectionStatus, error?: string) {
  useEntityStore.setState({ status, error });
}

beforeEach(() => {
  localStorage.clear();
  startConnection.mockClear();
  useEntityStore.setState({ entities: {}, areas: {}, status: "demo", error: undefined });
});

describe("Settings", () => {
  it("renders the live connection status", () => {
    setStatus("connected");
    renderSettings();
    expect(screen.getByText("Connected")).toBeInTheDocument();
  });

  it("surfaces an error message when the connection fails", () => {
    setStatus("error", "Invalid access token.");
    renderSettings();
    expect(screen.getByText("Disconnected")).toBeInTheDocument();
    expect(screen.getByText("Invalid access token.")).toBeInTheDocument();
  });

  it("disables Connect until both URL and token are present", async () => {
    const user = userEvent.setup();
    renderSettings();

    // The URL defaults to this origin (the in-cluster proxy), so only the token
    // is missing initially.
    const connect = screen.getByRole("button", { name: "Connect" });
    expect(connect).toBeDisabled();

    await user.type(
      screen.getByLabelText("Long-lived access token"),
      "llat-secret-token",
    );
    expect(connect).toBeEnabled();
  });

  it("Connect persists the credentials and (re)starts the source", async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.clear(screen.getByLabelText("HA URL"));
    await user.type(screen.getByLabelText("HA URL"), "http://192.168.4.34:8123");
    await user.type(
      screen.getByLabelText("Long-lived access token"),
      "llat-secret-token",
    );
    await user.click(screen.getByRole("button", { name: "Connect" }));

    expect(loadCredentials()).toEqual({
      url: "http://192.168.4.34:8123",
      token: "llat-secret-token",
    });
    expect(startConnection).toHaveBeenCalledTimes(1);
    // The saved URL is echoed back once credentials exist.
    expect(screen.getByText(/Saved:/)).toHaveTextContent("http://192.168.4.34:8123");
  });

  it("Disconnect only appears when connected and clears the saved token", async () => {
    const user = userEvent.setup();
    saveCredentials({ url: "http://ha.local:8123", token: "old-token" });
    renderSettings();

    await user.click(screen.getByRole("button", { name: "Disconnect" }));

    expect(loadCredentials()).toBeNull();
    expect(startConnection).toHaveBeenCalledTimes(1);
  });

  it("hides Disconnect when there are no saved credentials", () => {
    renderSettings();
    expect(
      screen.queryByRole("button", { name: "Disconnect" }),
    ).not.toBeInTheDocument();
  });
});

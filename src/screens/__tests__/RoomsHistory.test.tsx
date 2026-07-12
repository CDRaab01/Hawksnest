import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

// History fetches the logbook through the source; keep it pending/empty per test.
const fetchLogbook = vi.fn((..._args: unknown[]) => new Promise(() => {})); // never resolves → stays loading
vi.mock("../../store/connection", () => ({
  fetchLogbook: (...args: unknown[]) => fetchLogbook(...args),
}));

import { RoomsScreen } from "../RoomsScreen";
import { HistoryScreen } from "../HistoryScreen";
import { useEntityStore } from "../../store/entityStore";

beforeEach(() => {
  fetchLogbook.mockClear();
  fetchLogbook.mockImplementation(() => new Promise(() => {}));
  useEntityStore.setState({
    entities: {},
    areas: {},
    devices: { devices: {}, entityToDevice: {} },
    categories: {},
    status: "connected",
    error: undefined,
  });
});

function renderAt(node: React.ReactNode) {
  return render(<MemoryRouter>{node}</MemoryRouter>);
}

describe("RoomsScreen", () => {
  it("shows room skeletons while connecting with no areas yet", () => {
    useEntityStore.setState({ status: "connecting" });
    const { container } = renderAt(<RoomsScreen />);
    expect(container.querySelectorAll('[data-testid="skeleton"]').length).toBeGreaterThan(0);
  });

  it("shows an empty state when connected but no rooms exist", () => {
    renderAt(<RoomsScreen />);
    expect(screen.getByText(/No rooms yet/)).toBeInTheDocument();
  });
});

describe("HistoryScreen", () => {
  it("shows shimmering timeline-row skeletons while the logbook is loading", async () => {
    renderAt(<HistoryScreen />);
    // Six skeleton rows, each with an icon + two text bars + a time bar.
    await waitFor(() =>
      expect(screen.getAllByTestId("skeleton").length).toBeGreaterThanOrEqual(6),
    );
  });
});

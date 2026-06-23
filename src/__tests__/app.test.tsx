import { describe, it, expect, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { useEntityStore } from "../store/entityStore";

beforeEach(() => {
  useEntityStore.setState({
    entities: {},
    areas: {},
    status: "connecting",
    error: undefined,
  });
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("Home", () => {
  it("shows pinned favorites + area hub with resolved labels", async () => {
    renderAt("/");
    // Source bootstrap populates the store on mount.
    await screen.findByText("Areas");
    // Favorites section header (also matches the alarm card's "Home" arm button).
    expect(screen.getAllByText("Home").length).toBeGreaterThan(0);
    // Favorite locks render as comfortable cards with actions.
    expect(screen.getAllByText("Unlock").length).toBeGreaterThan(0);
    // Labels are resolved; no raw attribute names leak.
    expect(screen.getAllByText("Front Door").length).toBeGreaterThan(0);
    expect(screen.queryByText(/Lock Current status/)).toBeNull();
    // Connection pill reflects demo mode.
    expect(screen.getByText("Demo data")).toBeInTheDocument();
  });
});

describe("Area detail", () => {
  it("reproduces the Security scene with resolved labels", async () => {
    renderAt("/area/Front%20Door");
    const main = screen.getByRole("main");
    await within(main).findByText("Open");
    expect(within(main).getByText("Safe")).toBeInTheDocument();
    expect(within(main).queryByText(/Lock Current status/)).toBeNull();
    expect(within(main).queryByText(/Lock Intrusion/)).toBeNull();
  });
});

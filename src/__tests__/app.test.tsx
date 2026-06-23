import { describe, it, expect, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

  it("unlocking drives a service call that updates state (demo source)", async () => {
    const user = userEvent.setup();
    renderAt("/area/Front%20Door");
    const main = screen.getByRole("main");
    await within(main).findByText("Locked");

    await user.click(within(main).getByRole("button", { name: "Unlock" }));

    expect(await within(main).findByText("Unlocked")).toBeInTheDocument();
  });
});

describe("Entity detail", () => {
  it("shows the control, attributes, and a history chart", async () => {
    renderAt("/entity/lock.front_door_lock");
    const main = screen.getByRole("main");
    // Resolved title (override turns "Lock" into "Front Door").
    await within(main).findByRole("heading", { name: "Front Door" });
    // The live primary control (the lock card) is reused.
    expect(within(main).getAllByText("Locked").length).toBeGreaterThan(0);
    // History chart renders once the synthesized series loads.
    expect(await within(main).findByRole("img", { name: "State history" }))
      .toBeInTheDocument();
    // Raw entity_id is shown as a mono caption.
    expect(within(main).getByText("lock.front_door_lock")).toBeInTheDocument();
  });

  it("switches the history range when a range button is tapped", async () => {
    const user = userEvent.setup();
    renderAt("/entity/sensor.front_door_battery");
    const main = screen.getByRole("main");
    await within(main).findByRole("img", { name: "State history" });

    const sixHour = within(main).getByRole("button", { name: "6h" });
    await user.click(sixHour);
    expect(sixHour).toHaveAttribute("aria-pressed", "true");
  });
});

import { describe, it, expect, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { useEntityStore } from "../store/entityStore";
import { usePrefsStore } from "../store/prefsStore";

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
  it("shows the security hero, a rooms entry, and resolved labels (no clutter)", async () => {
    renderAt("/");
    // Source bootstrap populates the store on mount; the compact Rooms entry replaces the old hub.
    // ("Rooms" appears in both the nav and the section header, so match all.)
    expect((await screen.findAllByText("Rooms")).length).toBeGreaterThan(0);
    // The three big arm circles are the focus of the hero.
    expect(screen.getByRole("button", { name: "Off" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Home" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Away" })).toBeInTheDocument();
    // The plain-language security line (the demo front-door contact reads open).
    expect(screen.getByText("Front Door open")).toBeInTheDocument();
    // The old big favorite control cards are gone from Home.
    expect(screen.queryByText("Unlock")).toBeNull();
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

    // Unlock is a deliberate action: commit the slide via its keyboard path.
    const thumb = within(main).getByRole("button", { name: "Slide to unlock" });
    thumb.focus();
    await user.keyboard("{Enter}");

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

/**
 * Pinning used to be a no-op you could perform, save and reorder.
 *
 * The Devices row said "Pin to dashboard", Customize said "pin it to Home", `config/favorites.ts`
 * documented "the entities surfaced at the top of the Home screen", and README §Screens described
 * "pinned favorites (large cards) above an area hub" — while `DashboardScreen` rendered the
 * security hero, the camera wall and a Rooms link, and nothing else.
 */
describe("Home — pinned favorites", () => {
  it("renders a card for each pinned entity", async () => {
    renderAt("/");
    const main = screen.getByRole("main");
    expect(await within(main).findByText("Pinned")).toBeInTheDocument();
    // The seed pins the two door locks; the demo source supplies both.
    const pinnedSection = within(main).getByText("Pinned").closest("section")!;
    expect(within(pinnedSection).getAllByText(/Locked|Unlocked/).length).toBeGreaterThan(0);
  });

  it("does not pin the alarm — the security hero is already its control", async () => {
    renderAt("/");
    const main = screen.getByRole("main");
    await within(main).findByText("Pinned");
    const pinnedSection = within(main).getByText("Pinned").closest("section")!;
    // A pinned AlarmCard would put a second Off/Home/Away right under the hero's arm discs.
    expect(within(pinnedSection).queryByRole("button", { name: "Away" })).toBeNull();
  });

  it("shows no Pinned section at all when nothing is pinned", async () => {
    usePrefsStore.setState({ favorites: [], hidden: [] });
    renderAt("/");
    const main = screen.getByRole("main");
    // Wait for the source to populate, so this can't pass by asserting before first paint.
    await within(main).findByText("Cameras");
    expect(within(main).queryByText("Pinned")).toBeNull();
    usePrefsStore.setState({ favorites: null, hidden: [] });
  });
});

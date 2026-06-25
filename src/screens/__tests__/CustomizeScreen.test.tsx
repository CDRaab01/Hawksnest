import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import App from "../../App";
import { useEntityStore } from "../../store/entityStore";
import { usePrefsStore } from "../../store/prefsStore";
import { favorites as seedFavorites } from "../../config/favorites";

// Render the real app at /customize so routing, the fixture source bootstrap,
// and the personalization store are all exercised end-to-end.
function renderCustomize() {
  return render(
    <MemoryRouter initialEntries={["/customize"]}>
      <App />
    </MemoryRouter>,
  );
}

// The Favorites panel uses the exact label "Unpin"; the catalog uses
// "Unpin from Home" / "Pin to Home" — so the action labels never collide and
// can be queried screen-wide.
beforeEach(() => {
  localStorage.clear();
  // Entities are repopulated by the App's source bootstrap; prefs fall back to
  // the static seed (favorites: null).
  useEntityStore.setState({ entities: {}, areas: {}, status: "connecting", error: undefined });
  usePrefsStore.setState({ favorites: null, hidden: [] });
});

describe("Customize", () => {
  it("lists the seed favorites and the full device catalog", async () => {
    renderCustomize();
    await screen.findByText("All devices");
    expect(screen.getByText("Favorites")).toBeInTheDocument();
    // Three seeded favorites → three "Unpin" actions.
    expect(await screen.findAllByLabelText("Unpin")).toHaveLength(seedFavorites.length);
    // "All devices" lists pin/hide controls for every entity.
    expect(
      screen.getAllByLabelText(/Pin to Home|Unpin from Home/).length,
    ).toBeGreaterThan(seedFavorites.length);
  });

  it("unpins a favorite, removing it from the Favorites panel", async () => {
    const user = userEvent.setup();
    renderCustomize();
    expect(await screen.findAllByLabelText("Unpin")).toHaveLength(3);

    await user.click(screen.getAllByLabelText("Unpin")[0]);
    expect(screen.getAllByLabelText("Unpin")).toHaveLength(2);
  });

  it("pins a previously-unpinned device from the catalog", async () => {
    const user = userEvent.setup();
    renderCustomize();
    await screen.findByText("All devices");

    const before = screen.getAllByLabelText("Pin to Home").length;
    await user.click(screen.getAllByLabelText("Pin to Home")[0]);
    // One fewer "Pin to Home" (it flipped to "Unpin from Home").
    expect(screen.getAllByLabelText("Pin to Home")).toHaveLength(before - 1);
  });

  it("hides a device from the area views", async () => {
    const user = userEvent.setup();
    renderCustomize();
    await screen.findByText("All devices");

    expect(screen.queryAllByLabelText("Show in areas")).toHaveLength(0);
    await user.click(screen.getAllByLabelText("Hide from areas")[0]);
    expect(screen.getAllByLabelText("Show in areas")).toHaveLength(1);
  });

  it("reorders a favorite with the Move down control", async () => {
    const user = userEvent.setup();
    renderCustomize();
    await screen.findAllByLabelText("Unpin");

    await user.click(screen.getAllByLabelText("Move down")[0]);

    // Seed [front, back, alarm] → first row moved down → [back, front, alarm].
    const expected = [seedFavorites[1], seedFavorites[0], ...seedFavorites.slice(2)];
    expect(usePrefsStore.getState().favorites).toEqual(expected);
  });

  it("Reset to defaults restores the seed favorites", async () => {
    const user = userEvent.setup();
    renderCustomize();
    await screen.findAllByLabelText("Unpin");

    // Remove two favorites, then reset.
    await user.click(screen.getAllByLabelText("Unpin")[0]);
    await user.click(screen.getAllByLabelText("Unpin")[0]);
    expect(screen.getAllByLabelText("Unpin")).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: /Reset to defaults/ }));
    expect(await screen.findAllByLabelText("Unpin")).toHaveLength(3);
    expect(usePrefsStore.getState().favorites).toBeNull();
  });
});

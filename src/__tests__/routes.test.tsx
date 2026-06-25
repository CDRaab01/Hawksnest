import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../App";
import { useEntityStore } from "../store/entityStore";

// Smoke coverage: every screen mounts through the real router + fixture source
// and renders its key landmark without crashing. Deeper per-screen behavior is
// covered in the dedicated screen tests.
const routes: Array<{ name: string; path: string; landmark: RegExp | string }> = [
  { name: "Home", path: "/", landmark: "Areas" },
  { name: "Area detail", path: "/area/Front%20Door", landmark: /Front Door/ },
  { name: "Entity detail", path: "/entity/lock.front_door_lock", landmark: /Front Door/ },
  { name: "Customize", path: "/customize", landmark: "All devices" },
  { name: "Automations", path: "/automations", landmark: "Automations" },
  { name: "New automation", path: "/automations/new", landmark: "New automation" },
  { name: "Settings", path: "/settings", landmark: "Connect to Home Assistant" },
];

beforeEach(() => {
  useEntityStore.setState({ entities: {}, areas: {}, status: "connecting", error: undefined });
});

describe("routes", () => {
  it.each(routes)("renders the $name screen", async ({ path, landmark }) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>,
    );
    // The header (which is always present) confirms the shell mounted.
    expect(screen.getByText("Hawksnest")).toBeInTheDocument();
    // The route's own landmark confirms its screen rendered.
    expect((await screen.findAllByText(landmark)).length).toBeGreaterThan(0);
  });
});

import { test, expect } from "../fixtures";

/** A HA-side state change arrives over the live subscription and reconciles the UI. */
test.describe("ha state echo", () => {
  test("reflects a pushed state change without reload", async ({ mockHaPage, control }) => {
    await mockHaPage.goto("/entity/light.living_room");
    const sw = mockHaPage.getByRole("switch");
    await expect(sw).toHaveAttribute("aria-checked", "false");

    await control.pushState("light.living_room", "on", { brightness: 200 });
    await expect(sw).toHaveAttribute("aria-checked", "true");
  });
});

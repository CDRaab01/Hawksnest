import { test, expect } from "../fixtures";

/**
 * Camera player transport (demo mode). Live video isn't playable headless, so we
 * assert the transport *controls* and live-state behaviour, not pixels: opening a
 * camera starts Live, stepping to a recorded event leaves Live, and "Go live"
 * snaps back.
 */
test.describe("camera transport", () => {
  test("open a camera and drive the transport controls", async ({ demoPage }) => {
    await demoPage.goto("/");
    await demoPage.getByRole("button", { name: /^Open .* live view$/ }).first().click();

    const dialog = demoPage.getByRole("dialog");
    await expect(dialog).toBeVisible();

    const goLive = demoPage.getByRole("button", { name: "Go live" });
    await expect(goLive).toBeVisible();
    await expect(demoPage.getByRole("button", { name: "Next event" })).toBeVisible();
    await expect(demoPage.getByRole("slider", { name: "Recording timeline" })).toBeVisible();

    // Starts live.
    await expect(goLive).toHaveAttribute("aria-pressed", "true");

    // Step back to a recorded event → no longer live → "Go live" snaps back.
    const prev = demoPage.getByRole("button", { name: "Previous event" });
    if (await prev.isEnabled()) {
      await prev.click();
      await expect(goLive).toHaveAttribute("aria-pressed", "false");
      await goLive.click();
      await expect(goLive).toHaveAttribute("aria-pressed", "true");
    }
  });
});

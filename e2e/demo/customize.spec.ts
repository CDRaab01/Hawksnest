import { test, expect } from "../fixtures";

/**
 * Customize editor (demo mode). Edits write straight through to localStorage, so
 * we also assert persistence across a reload. Uses the accessible button names the
 * vitest suite already relies on; drag-reorder is exercised via the deterministic
 * keyboard Move up/down buttons rather than flaky headless mouse drag.
 */
test.describe("customize", () => {
  test("pin a device, and it persists across reload", async ({ demoPage }) => {
    await demoPage.goto("/customize");
    const pin = demoPage.getByRole("button", { name: "Pin to Home" });
    const before = await pin.count();
    expect(before).toBeGreaterThan(0);

    await pin.first().click();
    // The clicked row flips to "Unpin from Home", so one fewer "Pin to Home".
    await expect(demoPage.getByRole("button", { name: "Pin to Home" })).toHaveCount(before - 1);

    await demoPage.reload();
    await expect(demoPage.getByRole("button", { name: "Pin to Home" })).toHaveCount(before - 1);
  });

  test("hide a device, then show it again", async ({ demoPage }) => {
    await demoPage.goto("/customize");
    const hide = demoPage.getByRole("button", { name: "Hide from areas" });
    const hideBefore = await hide.count();
    const showBefore = await demoPage.getByRole("button", { name: "Show in areas" }).count();

    await hide.first().click();
    await expect(demoPage.getByRole("button", { name: "Show in areas" })).toHaveCount(showBefore + 1);

    await demoPage.getByRole("button", { name: "Show in areas" }).first().click();
    await expect(demoPage.getByRole("button", { name: "Hide from areas" })).toHaveCount(hideBefore);
  });

  test("reorder favorites with Move down", async ({ demoPage }) => {
    await demoPage.goto("/customize");
    const handles = demoPage.getByRole("button", { name: /^Drag to reorder/ });
    await expect(handles.first()).toBeVisible();

    const labels = () => handles.evaluateAll((els) => els.map((e) => e.getAttribute("aria-label")));
    const before = await labels();
    test.skip(before.length < 2, "need 2+ favorites to reorder");

    await demoPage.getByRole("button", { name: "Move down" }).first().click();
    await expect.poll(async () => (await labels())[0]).not.toBe(before[0]);
  });
});

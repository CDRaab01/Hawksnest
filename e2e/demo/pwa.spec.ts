import { test, expect } from "../fixtures";

/**
 * PWA app shell. The installable manifest + service worker behave differently
 * under `vite dev` vs a production build, so offline/SW assertions are deferred to
 * a `vite preview` project (see fixme). Here we assert the static shell metadata
 * that's always served.
 */
test.describe("pwa", () => {
  test("serves the app-shell head metadata", async ({ demoPage }) => {
    await demoPage.goto("/");
    await expect(demoPage).toHaveTitle("Hawksnest");
    await expect(demoPage.locator('meta[name="theme-color"]')).toHaveAttribute("content", "#0B0D10");
  });

  // Offline shell + service worker are covered against the production build in
  // e2e/pwa/pwa-offline.spec.ts (the `pwa` Playwright project on `vite preview`).
});

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

  // The vite-plugin-pwa service worker is unreliable under the dev server; cover
  // real install + offline against a `vite preview` build in a follow-up.
  test.fixme("registers a service worker and serves the shell offline", async () => {
    // Build + preview, register navigator.serviceWorker, go offline, reload, assert shell.
  });
});

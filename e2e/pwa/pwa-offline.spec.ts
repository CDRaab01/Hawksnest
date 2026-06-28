import { test, expect } from "../fixtures";

/**
 * PWA offline shell — runs against the production build via `vite preview` (the
 * `pwa` project, baseURL :4173), since the service worker is only reliable in a
 * real build. After the SW activates, going offline and reloading must still
 * serve the app shell (precached), not a browser error page.
 */
test.describe("pwa offline (preview build)", () => {
  test("serves the app shell offline after the service worker activates", async ({ demoPage, context }) => {
    await demoPage.goto("/");
    await expect(demoPage).toHaveTitle("Hawksnest");

    // Wait for the service worker to be active and controlling.
    await demoPage.waitForFunction(async () => {
      const reg = await navigator.serviceWorker?.ready;
      return Boolean(reg?.active);
    });

    await context.setOffline(true);
    try {
      await demoPage.reload();
      await expect(demoPage).toHaveTitle("Hawksnest");
      await expect(demoPage.getByRole("navigation")).toBeVisible();
    } finally {
      await context.setOffline(false);
    }
  });
});

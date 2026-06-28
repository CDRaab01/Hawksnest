import { test, expect } from "../fixtures";

/**
 * Demo-mode smoke across the app's routes (src/App.tsx). No backend — the app
 * falls back to fixtures with no saved token. Asserts each route renders its
 * shell without throwing (a blank #root would mean a render crash).
 */
const ROUTES = ["/", "/rooms", "/devices", "/history", "/customize", "/automations", "/settings"];

test.describe("demo navigation", () => {
  test("dashboard loads with the app shell", async ({ demoPage }) => {
    await demoPage.goto("/");
    await expect(demoPage).toHaveTitle(/Hawksnest/);
    // Bottom nav is part of the always-on shell.
    await expect(demoPage.getByRole("navigation")).toBeVisible();
  });

  test("settings shows the demo-data status", async ({ demoPage }) => {
    await demoPage.goto("/settings");
    await expect(demoPage.getByText("Demo data (no Home Assistant connected)")).toBeVisible();
  });

  for (const path of ROUTES) {
    test(`renders ${path} without crashing`, async ({ demoPage }) => {
      const errors: string[] = [];
      demoPage.on("pageerror", (e) => errors.push(e.message));
      await demoPage.goto(path);
      // App shell present (header) and main has content.
      await expect(demoPage.locator("main")).toBeVisible();
      expect(errors, `page errors on ${path}`).toEqual([]);
    });
  }
});

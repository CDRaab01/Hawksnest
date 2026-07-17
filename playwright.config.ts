import { defineConfig } from "@playwright/test";
import { existsSync, readdirSync } from "node:fs";
import { MOCK_HA_PORT } from "./mock-ha/port";

const CI = !!process.env.CI;

/**
 * In the cloud sandbox, Chromium is pre-installed under /opt/pw-browsers (and
 * PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD blocks a fresh fetch), so point Playwright at
 * it. On GitHub runners that dir doesn't exist; `npx playwright install chromium`
 * provides the managed browser and we leave executablePath unset.
 */
function sandboxChromium(): string | undefined {
  const root = "/opt/pw-browsers";
  if (CI || !existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => d.startsWith("chromium-"));
  if (!dir) return undefined;
  const bin = `${root}/${dir}/chrome-linux/chrome`;
  return existsSync(bin) ? bin : undefined;
}

const executablePath = sandboxChromium();
const launch = executablePath ? { launchOptions: { executablePath } } : {};

export default defineConfig({
  testDir: "e2e",
  // The mock server holds shared, single-writer state, so run serially.
  fullyParallel: false,
  workers: 1,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  reporter: [["html", { open: "never" }], ["list"]],
  use: {
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      // Demo + mock-HA specs against the dev server.
      name: "chromium",
      testIgnore: "**/pwa/**",
      use: { baseURL: "http://localhost:5173", ...launch },
    },
    {
      // PWA offline shell needs a real build, so it runs against `vite preview`
      // (the service worker is unreliable under the dev server).
      name: "pwa",
      testMatch: "**/pwa/**",
      use: { baseURL: "http://localhost:4173", ...launch },
    },
  ],
  // All must be ready before specs run; Playwright waits on each url.
  webServer: [
    {
      command: "npm run mock-ha",
      url: `http://localhost:${MOCK_HA_PORT}/__scenario/health`,
      // Pass the port down so the spawned mock binds where the fixtures + control
      // client expect it — override MOCK_HA_PORT to dodge a host-port collision.
      env: { MOCK_HA_PORT: String(MOCK_HA_PORT) },
      reuseExistingServer: !CI,
      stdout: "ignore",
    },
    {
      command: "npm run dev",
      url: "http://localhost:5173",
      reuseExistingServer: !CI,
      stdout: "ignore",
    },
    {
      command: "npm run build && npm run preview -- --port 4173 --strictPort",
      url: "http://localhost:4173",
      reuseExistingServer: !CI,
      timeout: 180_000,
      stdout: "ignore",
    },
  ],
});

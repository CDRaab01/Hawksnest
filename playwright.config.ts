import { defineConfig } from "@playwright/test";
import { existsSync, readdirSync } from "node:fs";

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

export default defineConfig({
  testDir: "e2e",
  // The mock server holds shared, single-writer state, so run serially.
  fullyParallel: false,
  workers: 1,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  reporter: [["html", { open: "never" }], ["list"]],
  use: {
    baseURL: "http://localhost:5173",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: executablePath ? { launchOptions: { executablePath } } : {},
    },
  ],
  // Both must be ready before specs run; Playwright waits on each url.
  webServer: [
    {
      command: "npm run mock-ha",
      url: "http://localhost:8765/__scenario/health",
      reuseExistingServer: !CI,
      stdout: "ignore",
    },
    {
      command: "npm run dev",
      url: "http://localhost:5173",
      reuseExistingServer: !CI,
      stdout: "ignore",
    },
  ],
});

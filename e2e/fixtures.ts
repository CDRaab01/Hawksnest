import { test as base, expect, type Page } from "@playwright/test";
import { MockControl } from "../mock-ha/controlClient";
import { MOCK_HA_PORT } from "../mock-ha/port";

/** localStorage key the app reads credentials from (src/store/credentials.ts). */
const CREDS_KEY = "hawksnest.ha";

interface Fixtures {
  /** HTTP client for the mock server's /__scenario control API. */
  control: MockControl;
  /** A page with NO credentials → the app runs the demo/fixture source. */
  demoPage: Page;
  /** A page with creds seeded at the mock → the app runs the live haSource. */
  mockHaPage: Page;
}

// Note: the fixture setter is named `run` (not Playwright's usual `use`) so the
// eslint react-hooks plugin doesn't mistake it for React's `use` hook.
export const test = base.extend<Fixtures>({
  // eslint-disable-next-line no-empty-pattern -- Playwright needs a destructuring pattern to analyze fixture deps
  control: async ({}, run) => {
    await run(new MockControl());
  },

  demoPage: async ({ page }, run) => {
    await page.addInitScript((key) => {
      window.localStorage.removeItem(key);
    }, CREDS_KEY);
    await run(page);
  },

  mockHaPage: async ({ page, control }, run) => {
    // Fresh scenario per test (shared server state, single worker).
    await control.reset("default");
    await page.addInitScript(
      ({ key, creds }) => {
        window.localStorage.setItem(key, creds);
      },
      { key: CREDS_KEY, creds: JSON.stringify({ url: `http://localhost:${MOCK_HA_PORT}`, token: "e2e-token" }) },
    );
    await run(page);
  },
});

export { expect };

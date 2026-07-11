/**
 * The single source of truth for the mock-HA port. Defaults to 8765 but can be
 * overridden with `MOCK_HA_PORT` (or `PORT`, which `npm run mock-ha` already
 * honors) so the E2E stack can dodge a host-port collision — 8765 is taken on
 * the Dragonfly production host by the kidbot container, which made the suite
 * fail to start there until this was parameterized.
 *
 * Read by the server (bind), the control client (base URL), the Playwright
 * webServer (health check + the port it passes down), and the E2E fixtures (the
 * creds URL seeded into the app). Change it in one place and everything agrees.
 */
export const MOCK_HA_PORT = Number(
  process.env.MOCK_HA_PORT ?? process.env.PORT ?? 8765,
);

import { test, expect } from "../fixtures";

/**
 * A dropped socket is recovered by the lib's auto-reconnect. We assert it
 * deterministically: dropping the socket forces a *new* connection (the mock's
 * connection counter increments) and the UI returns to "Connected".
 */
test.describe("ha reconnect", () => {
  test("recovers after a dropped socket", async ({ mockHaPage, control }) => {
    await mockHaPage.goto("/settings");
    const status = mockHaPage.getByTestId("connection-status");
    await expect(status).toHaveText("Connected");

    const before = (await control.stats()).connections;
    await control.disconnect();

    await expect.poll(async () => (await control.stats()).connections).toBeGreaterThan(before);
    await expect(status).toHaveText("Connected");
  });

  /**
   * The honest degraded offline state (grace-window half): during a *persistent*
   * outage the dashboard keeps the last in-memory entities dimmed under a
   * "Reconnecting — as of" banner, the security posture reads unknown (never the
   * stale "All doors locked"), and once the outage ends everything recovers. The
   * 120s grace → full OfflineState expiry is pinned in vitest (fake clock) —
   * holding a real browser for two minutes here would be pure wall-clock waste.
   */
  test("holds an honest dimmed grace window through an outage, then recovers", async ({
    mockHaPage,
    control,
  }) => {
    await mockHaPage.goto("/");
    // The default scenario's live security read-out (its front-door contact is open).
    const securityLine = mockHaPage.getByText("Front Door open");
    await expect(securityLine).toBeVisible();

    // Script a persistent outage: drop the socket AND refuse every reconnect.
    await control.setRefuseConnections(true);
    await control.disconnect();

    // Grace window: banner up, content held (dimmed), and no security claim
    // survives the drop — the posture reads unknown, not a stale read-out.
    const banner = mockHaPage.getByTestId("reconnect-banner");
    await expect(banner).toHaveText(/Reconnecting — as of/);
    await expect(mockHaPage.getByText("Security state unknown — offline")).toBeVisible();
    await expect(securityLine).toBeHidden();
    await expect(mockHaPage.getByTestId("offline-state")).toBeHidden();

    // Outage ends: the lib's auto-reconnect lands, the banner clears, and the
    // live security read-out returns.
    await control.setRefuseConnections(false);
    await expect(securityLine).toBeVisible({ timeout: 15_000 });
    await expect(banner).toBeHidden();
  });
});

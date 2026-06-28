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
});

import { test, expect } from "../fixtures";

/** Connection lifecycle against the mock, driving the real haSource WS handshake. */
test.describe("ha connect", () => {
  test("connects with seeded credentials", async ({ mockHaPage }) => {
    await mockHaPage.goto("/settings");
    await expect(mockHaPage.getByTestId("connection-status")).toHaveText("Connected");
  });

  test("a bad token surfaces invalid auth", async ({ mockHaPage, control }) => {
    await control.reset("bad-token");
    await mockHaPage.goto("/settings");
    await expect(mockHaPage.getByTestId("connection-status")).toHaveText("Disconnected");
    await expect(mockHaPage.getByText("Invalid access token.")).toBeVisible();
  });
});

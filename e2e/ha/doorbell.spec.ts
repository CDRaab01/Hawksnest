import { test, expect } from "../fixtures";

/** A camera's `_ding` going `on` raises the app-wide doorbell banner. */
test.describe("ha doorbell", () => {
  test("a ding raises the doorbell banner, and it can be dismissed", async ({ mockHaPage, control }) => {
    await mockHaPage.goto("/");
    // Wait until connected (the camera wall renders from live entities).
    await expect(mockHaPage.getByRole("button", { name: /^Open .* live view$/ }).first()).toBeVisible();

    await control.pushState("binary_sensor.front_door_ding", "on");

    await expect(mockHaPage.getByText("Doorbell", { exact: true })).toBeVisible();
    await expect(mockHaPage.getByText(/Someone's at/)).toBeVisible();

    await mockHaPage.getByRole("button", { name: "Dismiss doorbell alert" }).click();
    await expect(mockHaPage.getByText("Doorbell", { exact: true })).toBeHidden();
  });
});

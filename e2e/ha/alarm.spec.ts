import { test, expect } from "../fixtures";

/** Alarm arm/disarm from the dashboard security panel, round-tripping through HA. */
test.describe("ha alarm", () => {
  test("arm away then disarm, reflecting each echo", async ({ mockHaPage, control }) => {
    await mockHaPage.goto("/");
    const off = mockHaPage.getByRole("button", { name: "Off", exact: true });
    const away = mockHaPage.getByRole("button", { name: "Away", exact: true });

    await expect(off).toHaveAttribute("aria-pressed", "true"); // starts disarmed

    await away.click();
    await expect(away).toHaveAttribute("aria-pressed", "true"); // echo: armed_away

    await off.click();
    await expect(off).toHaveAttribute("aria-pressed", "true"); // echo: disarmed

    const services = (await control.getCalls())
      .filter((c) => c.domain === "alarm_control_panel")
      .map((c) => c.service);
    expect(services).toEqual(["alarm_arm_away", "alarm_disarm"]);
  });
});

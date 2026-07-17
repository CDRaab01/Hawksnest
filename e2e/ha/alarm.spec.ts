import { test, expect } from "../fixtures";

/**
 * Alarm arm/disarm from the dashboard security panel, round-tripping through HA.
 * Arm/disarm is **non-optimistic** (like the lock): the tapped mode spins until
 * HA's echo, and a rejected call surfaces an error rather than silently doing
 * nothing.
 */
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

  test("the tapped mode shows a pending spinner until HA confirms", async ({ mockHaPage, control }) => {
    // A visible delay so the pending window is observable before the echo lands.
    await control.setServiceOutcome({
      domain: "alarm_control_panel",
      service: "alarm_arm_away",
      outcome: "confirm",
      delayMs: 800,
    });
    await mockHaPage.goto("/");
    const away = mockHaPage.getByRole("button", { name: "Away", exact: true });
    const off = mockHaPage.getByRole("button", { name: "Off", exact: true });

    await away.click();
    // Pending: the tapped mode is aria-busy and the others are disabled — never
    // optimistically "pressed" before HA answers.
    await expect(away).toHaveAttribute("aria-busy", "true");
    await expect(away).toHaveAttribute("aria-pressed", "false");
    await expect(off).toBeDisabled();

    // Echo confirms: spinner clears, the mode is now pressed, controls re-enable.
    await expect(away).toHaveAttribute("aria-pressed", "true");
    await expect(away).toHaveAttribute("aria-busy", "false");
    await expect(off).toBeEnabled();
  });

  test("a rejected arm surfaces an error and does not change the mode", async ({ mockHaPage, control }) => {
    await control.setServiceOutcome({
      domain: "alarm_control_panel",
      service: "alarm_arm_away",
      outcome: "reject",
    });
    await mockHaPage.goto("/");
    const off = mockHaPage.getByRole("button", { name: "Off", exact: true });
    const away = mockHaPage.getByRole("button", { name: "Away", exact: true });

    await expect(off).toHaveAttribute("aria-pressed", "true");
    await away.click();

    await expect(mockHaPage.getByText("Couldn't reach the alarm panel.")).toBeVisible();
    await expect(off).toHaveAttribute("aria-pressed", "true"); // mode never changed
    await expect(away).toHaveAttribute("aria-pressed", "false");
    await expect(off).toBeEnabled(); // controls re-enabled after the failure
  });
});

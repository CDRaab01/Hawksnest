import { test, expect } from "../fixtures";

/** A control action round-trips: the right call_service is sent and the echo updates the UI. */
test.describe("ha service call", () => {
  test("toggling a light sends the service and reflects the echo", async ({ mockHaPage, control }) => {
    await mockHaPage.goto("/entity/light.living_room");
    const sw = mockHaPage.getByRole("switch");
    await expect(sw).toHaveAttribute("aria-checked", "false");

    await sw.click();
    await expect(sw).toHaveAttribute("aria-checked", "true"); // echo turned it on

    const lightCalls = (await control.getCalls()).filter((c) => c.domain === "light");
    expect(lightCalls).toHaveLength(1);
    expect(lightCalls[0]).toMatchObject({
      domain: "light",
      service: "turn_on",
      target: { entity_id: "light.living_room" },
    });
  });
});

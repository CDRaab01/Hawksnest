import { test, expect } from "../fixtures";

/**
 * Clip export against the live haSource (mock HA, `frigate-camera` scenario).
 *
 * The path being covered end to end is: gate on the Frigate backend → mark a range → sign it via
 * `auth/sign_path` → build the `RecordingProxyView` URL → hand it to the browser as a download.
 * The single most valuable assertion is the `download` event's suggested filename, because it can
 * only be produced if every one of those steps worked.
 */
test.describe("camera clip export", () => {
  async function openFrigateCamera(page: import("@playwright/test").Page) {
    await page.goto("/");
    await page.getByRole("button", { name: "Open Front Gate live view" }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    // Clip export is for recorded footage — it is deliberately hidden while live. Scrub back by
    // tapping the newest event chip (the timeline opens at a 1h zoom, so older chips are off
    // screen; see camera-recording.spec.ts).
    await page.getByRole("button", { name: /at \d/i }).last().click();
  }

  test("marks a range and downloads it", async ({ mockHaPage, control }) => {
    await control.reset("frigate-camera");
    await openFrigateCamera(mockHaPage);

    await mockHaPage.getByRole("button", { name: "Export a clip" }).click();

    // Opens as playhead ±15s.
    // `exact`, because the timeline's own tick labels ("11:00 AM") substring-match a duration.
    await expect(mockHaPage.getByText("0:30", { exact: true })).toBeVisible();
    const end = mockHaPage.getByRole("slider", { name: "Clip end" });
    const before = await end.getAttribute("aria-valuenow");

    // Two coarse nudges push the end out by exactly 30s → 1:00.
    await mockHaPage.getByRole("button", { name: "+15s" }).nth(1).click();
    await mockHaPage.getByRole("button", { name: "+15s" }).nth(1).click();
    await expect(mockHaPage.getByText("1:00", { exact: true })).toBeVisible();
    expect(Number(await end.getAttribute("aria-valuenow")) - Number(before)).toBe(30_000);

    const [download] = await Promise.all([
      mockHaPage.waitForEvent("download"),
      mockHaPage.getByRole("button", { name: "Download" }).click(),
    ]);
    // camera-YYYY-MM-DD-HH-MM-SS-60s.mp4 — the whole chain in one string.
    expect(download.suggestedFilename()).toMatch(
      /^front_gate-\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}-60s\.mp4$/,
    );
  });

  test("signs the request — the export route refuses an unsigned one", async ({
    mockHaPage,
    control,
  }) => {
    await control.reset("frigate-camera");
    const requested: string[] = [];
    await mockHaPage.route("**/api/frigate/recording/**", (route) => {
      requested.push(route.request().url());
      return route.continue();
    });

    await openFrigateCamera(mockHaPage);
    await mockHaPage.getByRole("button", { name: "Export a clip" }).click();
    await Promise.all([
      mockHaPage.waitForEvent("download"),
      mockHaPage.getByRole("button", { name: "Download" }).click(),
    ]);

    expect(requested.length).toBeGreaterThan(0);
    // Without this the mock (like the real RecordingProxyView) 401s, and the browser would save
    // an error body under a .mp4 name with nothing the app could catch.
    for (const url of requested) expect(url).toContain("authSig=");
    // Integer seconds, and `recording` singular — the integration's path, not Frigate's raw one.
    expect(requested[0]).toMatch(/\/api\/frigate\/recording\/front_gate\/start\/\d+\/end\/\d+/);
  });

  test("turns Frigate's 400 into a sentence instead of a broken file", async ({
    mockHaPage,
    control,
  }) => {
    await control.reset("frigate-camera");
    await control.setClipOutcome("empty");
    await openFrigateCamera(mockHaPage);

    await mockHaPage.getByRole("button", { name: "Export a clip" }).click();
    await mockHaPage.getByRole("button", { name: "Download" }).click();

    // This is the case the pre-flight probe exists for: a bare <a download> would have saved the
    // JSON error body as an .mp4 and reported success.
    await expect(mockHaPage.getByText(/No recordings found/i)).toBeVisible();
  });

  test("is not offered on a camera Frigate does not record", async ({ mockHaPage, control }) => {
    await control.reset("ring-camera");
    await mockHaPage.goto("/");
    await mockHaPage.getByRole("button", { name: "Open Front Gate live view" }).click();
    await expect(mockHaPage.getByRole("dialog")).toBeVisible();
    await mockHaPage.getByRole("button", { name: /motion at/i }).last().click();
    // Ring has no arbitrary-range trim, so offering the control at all would be a promise the
    // backend cannot keep.
    await expect(mockHaPage.getByRole("button", { name: "Export a clip" })).toHaveCount(0);
  });
});

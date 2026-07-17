import { test, expect } from "../fixtures";

/**
 * Camera player transport + scrubbing (demo mode). Live video isn't playable
 * headless, so we assert the transport *controls* and live-state behaviour, not
 * pixels: opening a camera starts Live, stepping to a recorded moment leaves
 * Live, "Go live" snaps back, and dragging the timeline scrubs the playhead
 * live and keeps playing from the release point.
 */
test.describe("camera transport", () => {
  test("open a camera and drive the transport controls", async ({ demoPage }) => {
    await demoPage.goto("/");
    await demoPage.getByRole("button", { name: /^Open .* live view$/ }).first().click();

    const dialog = demoPage.getByRole("dialog");
    await expect(dialog).toBeVisible();

    const goLive = demoPage.getByRole("button", { name: "Go live" });
    await expect(goLive).toBeVisible();
    await expect(demoPage.getByRole("button", { name: "Next moment" })).toBeVisible();
    await expect(demoPage.getByRole("slider", { name: "Recording timeline" })).toBeVisible();

    // Starts live.
    await expect(goLive).toHaveAttribute("aria-pressed", "true");

    // Step back to a recorded moment → no longer live → "Go live" snaps back.
    const prev = demoPage.getByRole("button", { name: "Previous moment" });
    await expect(prev).toBeEnabled();
    await prev.click();
    await expect(goLive).toHaveAttribute("aria-pressed", "false");
    await goLive.click();
    await expect(goLive).toHaveAttribute("aria-pressed", "true");
  });

  test("dragging the timeline scrubs live and plays from the release point", async ({
    demoPage,
  }) => {
    await demoPage.goto("/");
    await demoPage.getByRole("button", { name: /^Open .* live view$/ }).first().click();

    const slider = demoPage.getByRole("slider", { name: "Recording timeline" });
    await expect(slider).toBeVisible();
    const goLive = demoPage.getByRole("button", { name: "Go live" });
    await expect(goLive).toHaveAttribute("aria-pressed", "true");

    // Drag the strip right (pan back in time), holding the button down.
    // hover() first: it waits for actionability (stable position) — a raw
    // mouse press at pre-settle coordinates misses the track entirely. Press
    // on the top strip of the track, above the event chips.
    await slider.hover();
    const box = (await slider.boundingBox())!;
    const y = box.y + 5;
    await demoPage.mouse.move(box.x + box.width / 2, y);
    await demoPage.mouse.down();
    await demoPage.mouse.move(box.x + box.width / 2 + 150, y, { steps: 10 });

    // Mid-drag the playhead already left Live — that's the live scrub.
    await expect(goLive).toHaveAttribute("aria-pressed", "false");

    await demoPage.mouse.up();

    // Released in the past: recorded playback continues from that moment — the
    // demo VOD is mounted and the playhead stays at the released (non-live)
    // time. (Playwright's Chromium lacks the codecs to decode the demo clip, so
    // the media position itself isn't assertable headless.)
    await expect(goLive).toHaveAttribute("aria-pressed", "false");
    await expect(demoPage.getByLabel("Camera footage")).toBeAttached();
    const released = Number(await slider.getAttribute("aria-valuenow"));
    const max = Number(await slider.getAttribute("aria-valuemax"));
    expect(released).toBeLessThan(max);
    expect(released).toBeGreaterThan(0);
  });
});

import { test, expect } from "../fixtures";

/**
 * Ring recorded-event playback against the live haSource (mock HA, `ring-camera`
 * scenario): seeking to a recorded moment resolves the `_event` stream, and a
 * stream HA can't produce surfaces as an honest, retryable failure — never a
 * stuck "Loading recording…". The mock's HLS payload isn't decodable headless,
 * so the retry round-trip is asserted via the hub's `camera/stream` request log
 * rather than pixels.
 */
test.describe("ring recorded playback", () => {
  test("a failed stream shows an error with a working Retry", async ({
    mockHaPage,
    control,
  }) => {
    await control.reset("ring-camera");
    // First resolution fails (like HA timing out on a sleeping battery cam);
    // the small delay makes the transient "Loading recording…" observable.
    await control.setStreamOutcome({
      entity_id: "camera.front_gate_event",
      outcome: "error",
      delayMs: 400,
    });

    await mockHaPage.goto("/");
    await mockHaPage.getByRole("button", { name: "Open Front Gate live view" }).click();
    await expect(mockHaPage.getByRole("dialog")).toBeVisible();

    // Seek to a recorded moment via its timeline chip.
    await mockHaPage.getByRole("button", { name: /motion at/i }).first().click();
    await expect(mockHaPage.getByText("Recorded")).toBeVisible();

    // Honest tri-state: loading while resolving, then a visible failure — not a
    // stuck loader.
    await expect(mockHaPage.getByText("Loading recording…")).toBeVisible();
    await expect(mockHaPage.getByText("Couldn't load this recording")).toBeVisible();
    await expect(mockHaPage.getByText("Loading recording…")).toBeHidden();

    // The selector was actually driven (select_option for the chosen event).
    const calls = await control.getCalls();
    expect(calls.some((c) => c.domain === "select" && c.service === "select_option")).toBe(true);

    // Retry with HA healthy again (slowed so the resolving window is observable):
    // the failure clears back to loading and the stream is re-requested. What
    // plays after that isn't asserted — the mock's HLS payload isn't decodable
    // headless, so hls.js may legitimately step the player back to failed.
    await control.setStreamOutcome({
      entity_id: "camera.front_gate_event",
      outcome: "ok",
      delayMs: 800,
    });
    const before = (await control.stats()).streamRequests.filter(
      (id) => id === "camera.front_gate_event",
    ).length;
    await mockHaPage.getByRole("button", { name: "Retry" }).click();
    await expect(mockHaPage.getByText("Loading recording…")).toBeVisible();
    await expect(mockHaPage.getByText("Couldn't load this recording")).toBeHidden();
    await expect
      .poll(async () =>
        (await control.stats()).streamRequests.filter((id) => id === "camera.front_gate_event")
          .length,
      )
      .toBeGreaterThan(before);
  });

  test("scrubbing to a gap says so instead of loading forever", async ({
    mockHaPage,
    control,
  }) => {
    await control.reset("ring-camera");
    await mockHaPage.goto("/");
    await mockHaPage.getByRole("button", { name: "Open Front Gate live view" }).click();

    // Seek far into the past (before any kept recording) by stepping back from
    // the oldest chip: drag the strip right a long way, then release.
    const slider = mockHaPage.getByRole("slider", { name: "Recording timeline" });
    // hover() first: it waits for actionability (stable position) — a raw
    // mouse press at pre-settle coordinates misses the track entirely.
    await slider.hover();
    const box = (await slider.boundingBox())!;
    // Press on the top strip of the track, above the event chips.
    const y = box.y + 5;
    await mockHaPage.mouse.move(box.x + box.width / 2, y);
    await mockHaPage.mouse.down();
    await mockHaPage.mouse.move(box.x + box.width - 4, y, { steps: 10 });
    await mockHaPage.mouse.up();

    await expect(mockHaPage.getByText("No saved recording for this moment")).toBeVisible();
    await expect(mockHaPage.getByText("Loading recording…")).toBeHidden();
  });
});

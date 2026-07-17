import type { Page, Locator } from "@playwright/test";
import { test, expect } from "../fixtures";

/**
 * SECURITY-CRITICAL: the non-optimistic lock flow against the real haSource.
 * The control is a slide-to-act track (a tap does nothing — the drag is the
 * confirmation, so a stray touch on the wall tablet can't unlock a door). A
 * committed slide shows a pending spinner until HA's echo confirms (or the call
 * fails). These specs script that echo (delay + outcome) so pending → confirmed,
 * a jam, and a failure are all covered — without touching a real deadbolt.
 * `data-state` is the raw HA entity state; the spinner text reflects intent.
 */
const LOCK = "lock.front_door_lock";

/** Drag the slide-to-act thumb across its track (the commit gesture). */
async function slide(page: Page, track: Locator) {
  const box = await track.boundingBox();
  if (!box) throw new Error("slide track not visible");
  const midY = box.y + box.height / 2;
  await page.mouse.move(box.x + 27, midY);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 8, midY, { steps: 10 });
  await page.mouse.up();
}

/** A partial drag that releases before the commit point — must do nothing. */
async function halfSlide(page: Page, track: Locator) {
  const box = await track.boundingBox();
  if (!box) throw new Error("slide track not visible");
  const midY = box.y + box.height / 2;
  await page.mouse.move(box.x + 27, midY);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.4, midY, { steps: 6 });
  await page.mouse.up();
}

test.describe("lock (security-critical)", () => {
  test("unlock then lock, each via a slide + pending -> confirmed transition", async ({ mockHaPage, control }) => {
    await control.setServiceOutcome({ domain: "lock", service: "unlock", outcome: "confirm", delayMs: 800 });
    await control.setServiceOutcome({ domain: "lock", service: "lock", outcome: "confirm", delayMs: 800 });
    await mockHaPage.goto(`/entity/${LOCK}`);

    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    const track = mockHaPage.getByTestId(`slide-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await slide(mockHaPage, track);
    await expect(card.getByText("Unlocking…").first()).toBeVisible(); // pending, before the echo
    await expect(card).toHaveAttribute("data-state", "unlocked"); // echo confirmed

    await slide(mockHaPage, track);
    await expect(card.getByText("Locking…").first()).toBeVisible();
    await expect(card).toHaveAttribute("data-state", "locked");
  });

  test("a released half-slide commits nothing (the tap-safety property)", async ({ mockHaPage, control }) => {
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    const track = mockHaPage.getByTestId(`slide-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await halfSlide(mockHaPage, track);

    // No pending, no call, no state change — the gesture must be completed.
    await expect(card.getByText("Unlocking…")).toHaveCount(0);
    await expect(card).toHaveAttribute("data-state", "locked");
    expect((await control.getCalls()).filter((c) => c.domain === "lock")).toHaveLength(0);
  });

  test("a jam clears the spinner and surfaces 'Jammed' (never shows Unlocked)", async ({ mockHaPage, control }) => {
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    const track = mockHaPage.getByTestId(`slide-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    // Start unlocked so the attempted action is "lock".
    await control.pushState(LOCK, "unlocked");
    await expect(card).toHaveAttribute("data-state", "unlocked");

    await control.setServiceOutcome({ domain: "lock", service: "lock", outcome: "jammed", delayMs: 300 });
    await slide(mockHaPage, track);
    await expect(card.getByText("Locking…").first()).toBeVisible(); // pending while in-flight

    // Jam echo settles: spinner clears, "Jammed" is shown (NOT "Unlocked"/"Locked"),
    // and the track re-arms for a retry.
    await expect(card).toHaveAttribute("data-state", "jammed");
    await expect(card.getByText("Jammed — try again")).toBeVisible();
    await expect(card.getByText("Locking…")).toHaveCount(0);
    await expect(card.getByText("Jammed — slide to retry")).toBeVisible();
  });

  test("a rejected call surfaces an error", async ({ mockHaPage, control }) => {
    await control.setServiceOutcome({ domain: "lock", service: "unlock", outcome: "reject" });
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    const track = mockHaPage.getByTestId(`slide-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await slide(mockHaPage, track);
    await expect(card.getByText("Couldn't reach the lock.")).toBeVisible();
    await expect(card).toHaveAttribute("data-state", "locked"); // never changed
  });

  test("records the exact call_service round-trip", async ({ mockHaPage, control }) => {
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    const track = mockHaPage.getByTestId(`slide-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await slide(mockHaPage, track);
    await expect(card).toHaveAttribute("data-state", "unlocked");

    const lockCalls = (await control.getCalls()).filter((c) => c.domain === "lock");
    expect(lockCalls).toHaveLength(1);
    expect(lockCalls[0]).toMatchObject({ domain: "lock", service: "unlock", target: { entity_id: LOCK } });
  });
});

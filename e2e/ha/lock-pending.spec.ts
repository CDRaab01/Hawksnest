import { test, expect } from "../fixtures";

/**
 * SECURITY-CRITICAL: the non-optimistic lock flow against the real haSource. Locks
 * never show an optimistic state — a tap shows a pending spinner until HA's echo
 * confirms (or the call fails). These specs script that echo (delay + outcome) so
 * the pending -> confirmed transition, a jam, and a failure are all covered —
 * without touching a real deadbolt. `data-state` is the raw HA entity state; the
 * spinner text reflects the pending intent.
 */
const LOCK = "lock.front_door_lock";

test.describe("lock (security-critical)", () => {
  test("unlock then lock, each via a pending -> confirmed transition", async ({ mockHaPage, control }) => {
    await control.setServiceOutcome({ domain: "lock", service: "unlock", outcome: "confirm", delayMs: 800 });
    await control.setServiceOutcome({ domain: "lock", service: "lock", outcome: "confirm", delayMs: 800 });
    await mockHaPage.goto(`/entity/${LOCK}`);

    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await card.getByRole("button", { name: "Unlock", exact: true }).click();
    await expect(card.getByText("Unlocking…")).toBeVisible(); // pending, before the echo
    await expect(card).toHaveAttribute("data-state", "unlocked"); // echo confirmed

    await card.getByRole("button", { name: "Lock", exact: true }).click();
    await expect(card.getByText("Locking…")).toBeVisible();
    await expect(card).toHaveAttribute("data-state", "locked");
  });

  test("a jam leaves the lock pending and never reaches locked", async ({ mockHaPage, control }) => {
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    // Start unlocked so the attempted action is "lock".
    await control.pushState(LOCK, "unlocked");
    await expect(card).toHaveAttribute("data-state", "unlocked");

    await control.setServiceOutcome({ domain: "lock", service: "lock", outcome: "jammed", delayMs: 300 });
    await card.getByRole("button", { name: "Lock", exact: true }).click();
    await expect(card.getByText("Locking…")).toBeVisible();
    await expect(card).toHaveAttribute("data-state", "jammed");
    // The spinner persists — pending is only cleared when state === the requested target.
    await expect(card.getByText("Locking…")).toBeVisible();
  });

  test("a rejected call surfaces an error", async ({ mockHaPage, control }) => {
    await control.setServiceOutcome({ domain: "lock", service: "unlock", outcome: "reject" });
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await card.getByRole("button", { name: "Unlock", exact: true }).click();
    await expect(card.getByText("Couldn't reach the lock.")).toBeVisible();
    await expect(card).toHaveAttribute("data-state", "locked"); // never changed
  });

  test("records the exact call_service round-trip", async ({ mockHaPage, control }) => {
    await mockHaPage.goto(`/entity/${LOCK}`);
    const card = mockHaPage.getByTestId(`lock-card-${LOCK}`);
    await expect(card).toHaveAttribute("data-state", "locked");

    await card.getByRole("button", { name: "Unlock", exact: true }).click();
    await expect(card).toHaveAttribute("data-state", "unlocked");

    const lockCalls = (await control.getCalls()).filter((c) => c.domain === "lock");
    expect(lockCalls).toHaveLength(1);
    expect(lockCalls[0]).toMatchObject({ domain: "lock", service: "unlock", target: { entity_id: LOCK } });
  });
});

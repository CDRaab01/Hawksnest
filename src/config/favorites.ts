/**
 * Default "Pinned" entities — the cards surfaced at the top of the Home screen out of the box.
 *
 * This is the *seed*: the personalization editor (`store/prefsStore.ts` + the Customize screen)
 * lets the user pin/unpin and reorder, persisting their list to localStorage and overriding this
 * default. Order here is the display order; entities not present in the store are skipped.
 *
 * **The alarm panel is deliberately not here**, though it used to be. The Dashboard grew a
 * security hero (`SecurityStatusBar`) whose three big arm discs ARE the alarm control, so pinning
 * the panel put a second Off/Home/Away directly beneath the first. The locks stay, because the
 * hero only *summarizes* them ("Front Door open") — it has no lock or unlock control, so a pinned
 * lock card is the one place on Home you can actually act on one.
 */
export const favorites: string[] = [
  "lock.front_door_lock",
  "lock.back_door_lock",
];

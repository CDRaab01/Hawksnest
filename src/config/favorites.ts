/**
 * Default "Home" favorites — the entities surfaced at the top of the Home screen
 * out of the box. This is the *seed*: the Phase 3 personalization editor
 * (`store/prefsStore.ts` + the Customize screen) lets the user pin/unpin and
 * reorder, persisting their list to localStorage and overriding this default.
 * Order here is the display order; entities not present in the store are skipped.
 */
export const favorites: string[] = [
  "lock.front_door_lock",
  "lock.back_door_lock",
  "alarm_control_panel.home",
];

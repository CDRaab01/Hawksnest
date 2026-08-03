package com.hawksnest.config

/**
 * Default "Home" favorites — the entities surfaced at the top of the Home screen out of the box.
 * Order here is the display order; entities not present in the store are skipped. Ported from
 * `src/config/favorites.ts`.
 *
 * **On Android this list is the whole story.** The web app has a personalization editor
 * (`/customize` — pin, reorder, hide, backed by `src/store/prefsStore.ts`) that overrides the seed;
 * Android has no equivalent. `util/DevicePrefsStore` persists only hidden entities and renames —
 * there is no pin and no user ordering. Closing that gap is a tracked item
 * (`AUDIT-2026-08.md` §7.1).
 */
val favorites: List<String> = listOf(
    "lock.front_door_lock",
    "lock.back_door_lock",
    "alarm_control_panel.home",
)

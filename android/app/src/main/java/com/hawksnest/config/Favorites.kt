package com.hawksnest.config

/**
 * Default "Home" favorites — the entities surfaced at the top of the Home screen out of the box.
 * This is the seed; the Phase 2 personalization editor lets the user pin/unpin and reorder,
 * persisting their list and overriding this default. Order here is the display order; entities not
 * present in the store are skipped. Ported from `src/config/favorites.ts`.
 */
val favorites: List<String> = listOf(
    "lock.front_door_lock",
    "lock.back_door_lock",
    "alarm_control_panel.home",
)

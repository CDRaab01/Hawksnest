package com.hawksnest.config

/**
 * Default "Home" favorites — the entities surfaced at the top of the Home screen out of the box.
 * Order here is the display order; entities not present in the store are skipped. Ported from
 * `src/config/favorites.ts`.
 *
 * **Since 2026-08-07 this is a seed, not the whole story** (parity with web's
 * `src/store/prefsStore.ts`): the Devices tab's pinned rail reads the user's stored pin list
 * from `util/DevicePrefsStore`, falling back to this list only while that store was never
 * customized (`core/logic/Pins.kt effectivePins` — the first pin/unpin/move materializes the
 * seed). Long-press any device row → Pin / Unpin / Move up / Move down.
 */
val favorites: List<String> = listOf(
    "lock.front_door_lock",
    "lock.back_door_lock",
    "alarm_control_panel.home",
)

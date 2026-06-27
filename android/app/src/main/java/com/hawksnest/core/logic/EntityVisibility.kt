package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity

/**
 * Object-id suffixes for ring-mqtt / Ring "housekeeping" entities that aren't real controls and
 * just clutter the device list, area views, history feed, and the automation picker. Home
 * Assistant's `entity_category` (config/diagnostic) already demotes most secondary entities, but
 * ring-mqtt frequently leaves these **untagged**, so they leak through a category-only filter.
 *
 * Deliberately NOT listed (kept visible — real signals/controls or already category-tagged):
 * `_battery`, `_volume`, `_tamper`, `_motion`, `_ding`, `_siren`, `_live`. Camera feed siblings
 * (`_snapshot`/`_live_view`) collapse into one logical camera via `CameraModel`, but we also
 * suppress them here so they don't appear as separate picker options. Ported from
 * `src/lib/entityVisibility.ts`.
 */
val NOISE_SUFFIXES = listOf(
    "_last_activity",
    "_info",
    "_event_stream",
    "_live_stream",
    "_event_select",
    "_bypass_mode",
    "_chirp_tone",
    "_snapshot",
    "_live_view",
)

private fun objectIdOf(entityId: String): String = entityId.substringAfter('.', entityId)

/** A noise (housekeeping) entity by object-id suffix — independent of HA's `entity_category`. */
fun isNoiseEntity(entityId: String): Boolean {
    val obj = objectIdOf(entityId)
    return NOISE_SUFFIXES.any { obj.endsWith(it) }
}

/**
 * A "primary" entity: a real control/signal worth surfacing in the main UI. False for anything HA
 * marks config/diagnostic ([categories]) or matching the ring-mqtt noise denylist. Non-primary
 * entities aren't removed from the app — they stay reachable under each device's Diagnostics.
 */
fun isPrimaryEntity(entityId: String, categories: Map<String, String>): Boolean =
    entityId !in categories && !isNoiseEntity(entityId)

/** Filter a list of entities down to the primary ones. */
fun primaryEntities(entities: List<HassEntity>, categories: Map<String, String>): List<HassEntity> =
    entities.filter { isPrimaryEntity(it.entityId, categories) }

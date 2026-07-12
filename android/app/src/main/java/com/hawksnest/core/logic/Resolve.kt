package com.hawksnest.core.logic

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.friendlyName
import com.hawksnest.core.ha.iconHint

/**
 * Per-entity override — the highest-priority tier of the resolution chain. Lets the owner force a
 * human label/icon regardless of what HA exposes (turns "Lock Current status …" into "Front Door").
 */
data class EntityOverride(val name: String? = null, val icon: ImageVector? = null)

typealias OverrideMap = Map<String, EntityOverride>

/**
 * Label resolution chain (mandatory — never surface a raw entity_id/attribute):
 *   1. per-entity override map
 *   2. HA friendly_name
 *   3. prettified entity_id (strip domain, title-case)
 *
 * Ported from `src/lib/resolve.ts`.
 */
fun resolveName(entity: HassEntity, overrides: OverrideMap = emptyMap()): String {
    overrides[entity.entityId]?.name?.let { return it }
    entity.friendlyName()?.let { return it }
    return prettifyEntityId(entity.entityId)
}

/**
 * Devices-list display name — the richer chain the Devices redesign uses:
 *   1. user rename (long-press → Rename, persisted on-device)
 *   2. per-entity override map (project-seeded)
 *   3. HA friendly_name, unless it's junk (just the domain word: "Lock", "Light")
 *   4. the registry device's name (name_by_user || name)
 *   5. whatever friendly_name there was, else the prettified entity_id
 */
fun displayName(
    entity: HassEntity,
    overrides: OverrideMap = emptyMap(),
    rename: String? = null,
    deviceName: String? = null,
): String {
    rename?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    overrides[entity.entityId]?.name?.let { return it }
    val friendly = entity.friendlyName()
    if (friendly != null && !isJunkName(friendly, entity.entityId)) return friendly
    deviceName?.trim()?.takeIf { it.isNotEmpty() && it != "Device" }?.let { return it }
    return friendly ?: prettifyEntityId(entity.entityId)
}

/**
 * A friendly_name that is literally just the domain word carries no information
 * ("Lock" on lock.*, "Light" on light.*) — the registry device name beats it.
 */
fun isJunkName(name: String, entityId: String): Boolean {
    val domainWord = entityId.substringBefore('.').replace('_', ' ')
    return name.trim().equals(domainWord, ignoreCase = true)
}

/** `binary_sensor.front_door_current_status` -> "Front Door Current Status". */
fun prettifyEntityId(entityId: String): String =
    entityId.substringAfter('.', entityId)
        .split(Regex("[_\\s]+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/** A small set of mdi:* names we map to Material icons; everything else falls through. */
private val MDI_TO_ICON: Map<String, ImageVector> = mapOf(
    "mdi:lock" to Icons.Filled.Lock,
    "mdi:door-open" to Icons.Filled.MeetingRoom,
    "mdi:door-closed" to Icons.Filled.DoorFront,
    "mdi:lightbulb" to Icons.Filled.Lightbulb,
    "mdi:cctv" to Icons.Filled.Videocam,
)

private val DOMAIN_ICON: Map<String, ImageVector> = mapOf(
    "lock" to Icons.Filled.Lock,
    "light" to Icons.Filled.Lightbulb,
    "camera" to Icons.Filled.Videocam,
    "image" to Icons.Filled.Videocam,
    "binary_sensor" to Icons.Filled.Sensors,
    "sensor" to Icons.Filled.Sensors,
    "switch" to Icons.Filled.ToggleOn,
    "climate" to Icons.Filled.Thermostat,
    "cover" to Icons.Filled.Blinds,
    "scene" to Icons.Filled.Sensors,
    "alarm_control_panel" to Icons.Filled.Shield,
    "media_player" to Icons.Filled.Movie,
    "fan" to Icons.Filled.Air,
)

/**
 * Icon resolution chain mirrors labels:
 *   1. per-entity override
 *   2. HA `icon` attribute (mapped mdi -> Material where known)
 *   3. domain default
 *   4. neutral fallback (never crash on an unknown domain)
 */
fun resolveIcon(entity: HassEntity, overrides: OverrideMap = emptyMap()): ImageVector {
    overrides[entity.entityId]?.icon?.let { return it }
    entity.iconHint()?.let { hint -> MDI_TO_ICON[hint]?.let { return it } }
    return DOMAIN_ICON[domainOf(entity.entityId)] ?: Icons.Filled.QuestionMark
}

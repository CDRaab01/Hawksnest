package com.hawksnest.core.logic

import com.hawksnest.core.ha.AreaRegistry
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.stringAttr

/** binary_sensor `device_class`es that count as a door/window contact for the secure read-out. */
val DOOR_CLASSES: Set<String> = setOf("door", "window", "garage_door")

/**
 * binary_sensor `device_class`es that are always-on life-safety hazards — surfaced no matter the
 * armed state.
 */
val LIFE_SAFETY_CLASSES: Set<String> = setOf("smoke", "carbon_monoxide", "gas", "moisture")

/**
 * The plain-language home-security read-out shown on the Home hero (the "is the house secure?"
 * answer). Pure — no Compose, no ViewModel, no HA connection — so this safety-critical logic is
 * unit-testable in isolation. Extracted from `HomeViewModel.buildUi`; keep the two in lockstep.
 */
data class SecurityReadout(
    /** "All doors locked", or e.g. "Front Door unlocked · Garage open". */
    val summary: String,
    /** True when nothing is unlocked or open. */
    val allClear: Boolean,
    /** Human names of currently-triggered life-safety sensors (smoke/CO/gas/leak). */
    val lifeSafetyAlerts: List<String>,
    /** How many life-safety sensors are monitored at all. */
    val lifeSafetyMonitored: Int,
    /** "<name> is offline" / "<name> +N more offline", or null when everything is reporting. */
    val offlineLabel: String?,
)

/**
 * Compute the [SecurityReadout] from the current entity set. A lock counts as secure only when
 * `locked` (or transiently `locking`); a door/window contact is "open" when `on`. Names are
 * resolved through [resolveName] so the read-out matches the rest of the UI.
 */
fun securityReadout(
    entities: Collection<HassEntity>,
    overrides: OverrideMap = emptyMap(),
    areas: AreaRegistry = emptyMap(),
    deviceByEntity: Map<String, String> = emptyMap(),
): SecurityReadout {
    val all = entities.toList()

    // A door's name: its area ("Front Door") reads better than the raw Z-Wave friendly_name
    // ("Lock Current status of the door"); fall back to the normal resolution chain.
    fun label(e: HassEntity): String = areas[e.entityId] ?: resolveName(e, overrides)

    val unlocked = all.filter {
        domainOf(it.entityId) == "lock" && it.state != "locked" && it.state != "locking"
    }
    // Devices that own a lock — their companion door/bolt contact (the Schlage
    // `binary_sensor.*_current_status`) is redundant with the lock state, so don't double-count it.
    val lockDevices = all.asSequence()
        .filter { domainOf(it.entityId) == "lock" }
        .mapNotNull { deviceByEntity[it.entityId] }
        .toSet()
    val openDoors = all.filter {
        domainOf(it.entityId) == "binary_sensor" &&
            it.stringAttr("device_class") in DOOR_CLASSES && it.state == "on" &&
            deviceByEntity[it.entityId] !in lockDevices
    }
    val parts = unlocked.map { "${label(it)} unlocked" } +
        openDoors.map { "${label(it)} open" }
    val allClear = parts.isEmpty()
    val summary = if (allClear) "All doors locked" else parts.joinToString(" · ")

    val offline = all.filter { it.state == "unavailable" }
    val offlineLabel = when {
        offline.isEmpty() -> null
        offline.size == 1 -> "${label(offline[0])} is offline"
        else -> "${label(offline[0])} +${offline.size - 1} more offline"
    }

    val lifeSafety = all.filter {
        domainOf(it.entityId) == "binary_sensor" &&
            it.stringAttr("device_class") in LIFE_SAFETY_CLASSES
    }
    val lifeSafetyAlerts = lifeSafety.filter { it.state == "on" }.map { resolveName(it, overrides) }

    return SecurityReadout(
        summary = summary,
        allClear = allClear,
        lifeSafetyAlerts = lifeSafetyAlerts,
        lifeSafetyMonitored = lifeSafety.size,
        offlineLabel = offlineLabel,
    )
}

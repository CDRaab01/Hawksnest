package com.hawksnest.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.favorites
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.DeviceIndex
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.ControlDeck
import com.hawksnest.core.logic.DEVICE_ACTIVE_STATES
import com.hawksnest.core.logic.NON_DEVICE_DOMAINS
import com.hawksnest.core.logic.buildControlDeck
import com.hawksnest.core.logic.displayName
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.effectivePins
import com.hawksnest.core.logic.isPrimaryEntity
import com.hawksnest.core.logic.movePin
import com.hawksnest.core.logic.togglePin
import com.hawksnest.core.logic.prettifyEntityId
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.util.DevicePrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import javax.inject.Inject

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** The Devices tab's whole render model: pinned rail + the control deck + hidden shelf. */
data class DevicesUi(
    /**
     * The pinned rail, in the user's stored order (seeded from `config/Favorites`
     * until first customized). Ids missing from the entity map are skipped; a
     * pinned-and-hidden entity is skipped too (hide wins — the partition runs
     * first). Pinned devices also stay in their deck sections: the rail is a
     * shortcut, not a re-org.
     */
    val pinned: List<DeviceUi> = emptyList(),
    /** Devices v3: the function-ordered control deck (see core/logic/ControlDeck.kt). */
    val deck: ControlDeck<DeviceUi> = ControlDeck(),
    /** Devices the user hid (long-press → Hide), restorable from the footer sheet. */
    val hidden: List<DeviceUi> = emptyList(),
)

/**
 * Devices — the control deck (v3): every controllable entity regrouped by function
 * and ordered by importance (see core/logic/ControlDeck.kt), with cameras and
 * sensor spray demoted to summaries. Layered on top of the store's already-deduped
 * entities: HA config/diagnostic noise is filtered, user-hidden entities move to
 * the hidden shelf, and names resolve through user renames → overrides →
 * non-junk friendly_name → registry device name.
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val connection: ConnectionManager,
    private val devicePrefs: DevicePrefsStore,
) : ViewModel() {

    private val state = connection.state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    val ui: StateFlow<DevicesUi> = combine(
        state.entities, state.areas, state.entityCategories, state.devices,
        devicePrefs.hidden, devicePrefs.renames, devicePrefs.pinned, _query,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        build(
            values[0] as Map<String, HassEntity>,
            values[1] as Map<String, String>,
            values[2] as Map<String, String>,
            values[3] as DeviceIndex,
            values[4] as Set<String>,
            values[5] as Map<String, String>,
            values[6] as List<String>?,
            values[7] as String,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUi())

    /** Entity ids with a control in flight — rows/cards render pending state from this. */
    val pending: StateFlow<Set<String>> = connection.pendingControls

    /** entity_id → area name, for the tile grid's room captions. */
    val areas: StateFlow<Map<String, String>> = state.areas

    private fun build(
        entities: Map<String, HassEntity>,
        areas: Map<String, String>,
        categories: Map<String, String>,
        deviceIndex: DeviceIndex,
        hiddenIds: Set<String>,
        renames: Map<String, String>,
        storedPins: List<String>?,
        query: String,
    ): DevicesUi {
        fun toUi(e: HassEntity): DeviceUi {
            val deviceName = deviceIndex.deviceByEntity[e.entityId]
                ?.let { deviceIndex.devices[it]?.name }
            return DeviceUi(
                entityId = e.entityId,
                name = displayName(e, overrides, renames[e.entityId], deviceName),
                stateText = e.state.replaceFirstChar { c -> c.uppercaseChar() },
                rawState = e.state,
                card = domainToCard(e.entityId),
                attributes = e.attributes,
            )
        }

        val primary = entities.values.filter {
            isPrimaryEntity(it.entityId, categories) && domainOf(it.entityId) !in NON_DEVICE_DOMAINS
        }
        val (hidden, shown) = primary.partition { it.entityId in hiddenIds }

        // Battery health lives in DIAGNOSTIC entities the tab filters out (e.g. a Ring
        // sensor's `*_battery` sensor), so the scan runs over ALL entities and flags the
        // owning device — the deck then surfaces its visible representative.
        fun batteryOf(e: HassEntity): Double? =
            if ((e.attributes["device_class"] as? JsonPrimitive)?.content == "battery") {
                e.state.toDoubleOrNull()
            } else {
                e.attributes.num("battery_level")
            }

        // HA's OTHER battery signal, which reads backwards: a `binary_sensor` with
        // `device_class: battery` uses `on` to mean LOW. `batteryOf` matches the device_class and
        // then parses the state as a number, so "on" became null and the device was reported
        // healthy — a device whose only battery signal is a binary sensor (common on Z-Wave and
        // Zigbee) could never reach this rail however flat its cell was. Web's twin is
        // `deviceHealth.isLowBatteryBinary`.
        fun isLowBatteryBinary(e: HassEntity): Boolean =
            e.entityId.startsWith("binary_sensor.") &&
                (e.attributes["device_class"] as? JsonPrimitive)?.content == "battery" &&
                e.state.equals("on", ignoreCase = true)

        val lowBatteryDevices = entities.values.mapNotNullTo(HashSet()) { e ->
            val level = batteryOf(e)
            val low = (level != null && level <= 20.0) || isLowBatteryBinary(e)
            if (low) deviceIndex.deviceByEntity[e.entityId] else null
        }

        val deck = buildControlDeck(
            devices = shown.map(::toUi),
            areaOf = { areas[it.entityId] },
            cardOf = { it.card },
            nameOf = { it.name },
            isActive = { it.rawState in ACTIVE_STATES },
            // attentionOf carries the BATTERY signal only — offline is judged whole-device
            // via offlineOf inside the deck, so one unavailable member (WLED's empty
            // playlist select) never flags an otherwise-healthy device.
            attentionOf = {
                (it.attributes.num("battery_level")?.let { b -> b <= 20.0 } == true) ||
                    deviceIndex.deviceByEntity[it.entityId] in lowBatteryDevices
            },
            offlineOf = { it.rawState == "unavailable" },
            query = query,
            idOf = { it.entityId },
            // Read-only entities sharing a physical device collapse into one group row —
            // the registry's device id is the grouping key, its name the row title.
            deviceKeyOf = { deviceIndex.deviceByEntity[it.entityId] },
            deviceNameOf = { deviceIndex.devices[it]?.name ?: it },
        )
        // Rail order = stored order; missing entities skipped; hidden wins over pinned
        // for free because the partition above already removed hidden ids from `shown`.
        val shownById = shown.associateBy { it.entityId }
        val pinned = effectivePins(storedPins, favorites)
            .mapNotNull { shownById[it] }
            .map(::toUi)

        return DevicesUi(
            pinned = pinned,
            deck = deck,
            hidden = hidden.map(::toUi).sortedBy { it.name.lowercase() },
        )
    }

    /** Pin or unpin an entity; the first edit materializes the `config/Favorites` seed. */
    fun togglePin(entityId: String) = viewModelScope.launch {
        devicePrefs.setPinned(togglePin(effectivePins(devicePrefs.pinned.first(), favorites), entityId))
    }

    /** Move a pinned entity up (-1) or down (+1) in the rail; clamps at the ends. */
    fun movePin(entityId: String, delta: Int) = viewModelScope.launch {
        devicePrefs.setPinned(movePin(effectivePins(devicePrefs.pinned.first(), favorites), entityId, delta))
    }

    /** Crash-safe control call; failures surface on the app snackbar, pending on [pending]. */
    fun call(entityId: String, service: String, extra: Map<String, Any?> = emptyMap()) {
        connection.control(entityId, service, controlLabel(connection, entityId), extra)
    }

    fun hide(entityId: String) = viewModelScope.launch { devicePrefs.setHidden(entityId, true) }

    /** Hide a whole device group at once; members restore individually from the hidden shelf. */
    fun hideAll(entityIds: List<String>) = viewModelScope.launch {
        entityIds.forEach { devicePrefs.setHidden(it, true) }
    }

    fun unhide(entityId: String) = viewModelScope.launch { devicePrefs.setHidden(entityId, false) }
    fun rename(entityId: String, name: String?) =
        viewModelScope.launch { devicePrefs.setRename(entityId, name) }

    private companion object {
        /** States that count as "active" for the per-room "N on" summary. */
        val ACTIVE_STATES = DEVICE_ACTIVE_STATES
    }
}

/** The device's resolved display name for control failure messages. */
internal fun controlLabel(connection: ConnectionManager, entityId: String): String =
    connection.state.entities.value[entityId]?.let { resolveName(it, overrides) }
        ?: prettifyEntityId(entityId)

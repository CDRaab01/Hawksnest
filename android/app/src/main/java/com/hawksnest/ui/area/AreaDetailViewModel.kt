package com.hawksnest.ui.area

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.DeviceIndex
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.DEVICE_ACTIVE_STATES
import com.hawksnest.core.logic.NON_DEVICE_DOMAINS
import com.hawksnest.core.logic.ReadonlyItem
import com.hawksnest.core.logic.buildDeviceSections
import com.hawksnest.core.logic.displayName
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.isPrimaryEntity
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.ui.devices.controlLabel
import com.hawksnest.util.DevicePrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One room's render model: controls (featured + control tiers, full cards) and the
 * device-grouped read-only tail.
 */
data class AreaUi(
    val controls: List<DeviceUi> = emptyList(),
    val readonly: List<ReadonlyItem<DeviceUi>> = emptyList(),
)

/**
 * Area detail. Applies THE SAME visibility chain as the Devices tab —
 * `isPrimaryEntity` + `NON_DEVICE_DOMAINS` + user-hidden + renames + device-grouped
 * read-only entities — because this screen is where a room's sensors actually get read
 * once the Devices tab demotes them to a summary. It historically applied only
 * `isPrimaryEntity`, which was survivable while the camera devices had no HA area; the
 * moment they were assigned rooms (2026-08-08) each camera poured ~30 primary entities
 * (PTZ buttons, snapshot images, sensor spray) into its room here. One invariant now,
 * everywhere read-only entities render: group by device, honor the full filter chain.
 */
@HiltViewModel
class AreaDetailViewModel @Inject constructor(
    private val connection: ConnectionManager,
    devicePrefs: DevicePrefsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Decoded by Navigation from the `{area}` path arg. */
    val area: String = savedStateHandle.get<String>("area").orEmpty()

    private val state = connection.state

    val ui: StateFlow<AreaUi> = combine(
        state.entities, state.areas, state.entityCategories, state.devices,
        devicePrefs.hidden, devicePrefs.renames,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        build(
            values[0] as Map<String, HassEntity>,
            values[1] as Map<String, String>,
            values[2] as Map<String, String>,
            values[3] as DeviceIndex,
            values[4] as Set<String>,
            values[5] as Map<String, String>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AreaUi())

    private fun build(
        entities: Map<String, HassEntity>,
        areas: Map<String, String>,
        categories: Map<String, String>,
        deviceIndex: DeviceIndex,
        hiddenIds: Set<String>,
        renames: Map<String, String>,
    ): AreaUi {
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

        val ids = areas.filterValues { it == area }.keys
        val shown = entities.values.filter {
            it.entityId in ids &&
                isPrimaryEntity(it.entityId, categories) &&
                domainOf(it.entityId) !in NON_DEVICE_DOMAINS &&
                it.entityId !in hiddenIds
        }

        // Scoped to one room, so buildDeviceSections yields exactly one section — reusing it
        // (constant areaOf) keeps the tiering, sorting, and device-grouping rules in one place.
        val section = buildDeviceSections(
            devices = shown.map(::toUi),
            areaOf = { area },
            cardOf = { it.card },
            nameOf = { it.name },
            isActive = { it.rawState in DEVICE_ACTIVE_STATES },
            deviceKeyOf = { deviceIndex.deviceByEntity[it.entityId] },
            deviceNameOf = { deviceIndex.devices[it]?.name ?: it },
        ).singleOrNull() ?: return AreaUi()

        return AreaUi(
            controls = section.featured + section.controls,
            readonly = section.readonlyItems,
        )
    }

    /** Entity ids with a control in flight — cards render pending state from this. */
    val pending: StateFlow<Set<String>> = connection.pendingControls

    /** Crash-safe control call; failures surface on the app snackbar, pending on [pending]. */
    fun call(entityId: String, service: String, extra: Map<String, Any?> = emptyMap()) {
        connection.control(entityId, service, controlLabel(connection, entityId), extra)
    }
}

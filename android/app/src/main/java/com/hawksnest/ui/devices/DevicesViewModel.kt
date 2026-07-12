package com.hawksnest.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.NON_DEVICE_DOMAINS
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.groupByArea
import com.hawksnest.core.logic.isPrimaryEntity
import com.hawksnest.core.logic.prettifyEntityId
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.DeviceUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DeviceGroup(val area: String, val devices: List<DeviceUi>)

/** The Devices tab: every entity, grouped by room, each with its essential controls. */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val groups: StateFlow<List<DeviceGroup>> =
        combine(state.entities, state.areas, state.entityCategories) { entities, areas, categories ->
            // Hide HA config/diagnostic entities (battery, last-activity, volume, motion-detection
            // toggles…) from the main list — they live under each device's detail view instead. Also
            // drop non-device domains (automations have their own tab; people/zones/sun are infra).
            val primary = entities.values.filter {
                isPrimaryEntity(it.entityId, categories) && domainOf(it.entityId) !in NON_DEVICE_DOMAINS
            }
            groupByArea(primary, areas).map { g ->
                DeviceGroup(
                    area = g.area,
                    devices = g.entities.map { e ->
                        DeviceUi(
                            entityId = e.entityId,
                            name = resolveName(e, overrides),
                            stateText = e.state.replaceFirstChar { c -> c.uppercaseChar() },
                            rawState = e.state,
                            card = domainToCard(e.entityId),
                            attributes = e.attributes,
                        )
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Entity ids with a control in flight — cards render pending state from this. */
    val pending: StateFlow<Set<String>> = connection.pendingControls

    /** Crash-safe control call; failures surface on the app snackbar, pending on [pending]. */
    fun call(entityId: String, service: String, extra: Map<String, Any?> = emptyMap()) {
        connection.control(entityId, service, controlLabel(connection, entityId), extra)
    }
}

/** The device's resolved display name for control failure messages. */
internal fun controlLabel(connection: ConnectionManager, entityId: String): String =
    connection.state.entities.value[entityId]?.let { resolveName(it, overrides) }
        ?: prettifyEntityId(entityId)

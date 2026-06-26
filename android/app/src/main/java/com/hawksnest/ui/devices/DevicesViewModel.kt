package com.hawksnest.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.groupByArea
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.DeviceUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceGroup(val area: String, val devices: List<DeviceUi>)

/** The Devices tab: every entity, grouped by room, each with its essential controls. */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val groups: StateFlow<List<DeviceGroup>> =
        combine(state.entities, state.areas) { entities, areas ->
            groupByArea(entities.values.toList(), areas).map { g ->
                DeviceGroup(
                    area = g.area,
                    devices = g.entities.map { e ->
                        DeviceUi(
                            entityId = e.entityId,
                            name = resolveName(e, overrides),
                            stateText = e.state.replaceFirstChar { c -> c.uppercaseChar() },
                            rawState = e.state,
                            card = domainToCard(e.entityId),
                        )
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun call(entityId: String, service: String) {
        viewModelScope.launch {
            connection.callService(domainOf(entityId), service, ServiceData(entityId = entityId))
        }
    }
}

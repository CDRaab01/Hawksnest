package com.hawksnest.ui.area

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.DeviceUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AreaDetailViewModel @Inject constructor(
    private val connection: ConnectionManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Decoded by Navigation from the `{area}` path arg. */
    val area: String = savedStateHandle.get<String>("area").orEmpty()

    private val state = connection.state

    val devices: StateFlow<List<DeviceUi>> =
        combine(state.entities, state.areas) { entities, areas ->
            val ids = areas.filterValues { it == area }.keys
            entities.values
                .filter { it.entityId in ids }
                .sortedBy { it.entityId }
                .map { e ->
                    DeviceUi(
                        entityId = e.entityId,
                        name = resolveName(e, overrides),
                        stateText = e.state.replaceFirstChar { c -> c.uppercaseChar() },
                        rawState = e.state,
                        card = domainToCard(e.entityId),
                        attributes = e.attributes,
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Non-optimistic control call; the store reconciles from the source echo. */
    fun call(entityId: String, service: String, extra: Map<String, Any?> = emptyMap()) {
        viewModelScope.launch {
            connection.callService(domainOf(entityId), service, ServiceData(entityId = entityId, extra = extra))
        }
    }
}

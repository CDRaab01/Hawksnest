package com.hawksnest.ui.area

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.isPrimaryEntity
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.ui.devices.controlLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
        combine(state.entities, state.areas, state.entityCategories) { entities, areas, categories ->
            val ids = areas.filterValues { it == area }.keys
            // Show real controls only — HA config/diagnostic + ring-mqtt housekeeping entities
            // (battery, last-activity, volume, info…) stay under each device's detail view.
            entities.values
                .filter { it.entityId in ids && isPrimaryEntity(it.entityId, categories) }
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

    /** Entity ids with a control in flight — cards render pending state from this. */
    val pending: StateFlow<Set<String>> = connection.pendingControls

    /** Crash-safe control call; failures surface on the app snackbar, pending on [pending]. */
    fun call(entityId: String, service: String, extra: Map<String, Any?> = emptyMap()) {
        connection.control(entityId, service, controlLabel(connection, entityId), extra)
    }
}

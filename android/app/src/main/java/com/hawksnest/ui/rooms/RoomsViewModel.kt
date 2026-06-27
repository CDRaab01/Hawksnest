package com.hawksnest.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.logic.RoomHighlight
import com.hawksnest.core.logic.groupByArea
import com.hawksnest.core.logic.roomHighlights
import com.hawksnest.core.logic.roomIconKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RoomUi(
    val area: String,
    val deviceCount: Int,
    val iconKey: String,
    val highlights: List<RoomHighlight>,
)

@HiltViewModel
class RoomsViewModel @Inject constructor(
    connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val rooms: StateFlow<List<RoomUi>> =
        combine(state.entities, state.areas, state.entityCategories) { entities, areas, categories ->
            // Hide config/diagnostic entities (battery, last-activity, volume…) so the device counts
            // and highlights reflect real controls, not housekeeping noise.
            val primary = entities.values.filter { it.entityId !in categories }
            groupByArea(primary, areas).map { g ->
                RoomUi(
                    area = g.area,
                    deviceCount = g.entities.size,
                    iconKey = roomIconKey(g.area),
                    highlights = roomHighlights(g.entities),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

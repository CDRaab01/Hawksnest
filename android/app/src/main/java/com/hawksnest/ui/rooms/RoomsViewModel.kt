package com.hawksnest.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.logic.groupByArea
import com.hawksnest.core.logic.resolveName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RoomUi(val area: String, val deviceCount: Int, val preview: String)

@HiltViewModel
class RoomsViewModel @Inject constructor(
    connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val rooms: StateFlow<List<RoomUi>> = combine(state.entities, state.areas) { entities, areas ->
        groupByArea(entities.values.toList(), areas).map { g ->
            RoomUi(
                area = g.area,
                deviceCount = g.entities.size,
                preview = g.entities.take(3).joinToString(" · ") { resolveName(it, overrides) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

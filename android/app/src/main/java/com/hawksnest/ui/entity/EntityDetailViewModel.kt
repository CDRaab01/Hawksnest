package com.hawksnest.ui.entity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.historyLevels
import com.hawksnest.core.logic.domainToCard
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.DeviceUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** History fetch UI state for the entity-detail chart. */
sealed interface HistoryUi {
    data object Loading : HistoryUi
    data object Empty : HistoryUi
    data object Error : HistoryUi
    data class Data(val levels: List<Float>) : HistoryUi
}

/**
 * Entity detail — the live control card plus a state-history chart (6h/24h/7d). History only depends
 * on the entity id + range, not the live state object: the live source rebuilds the entity map on
 * every WS push, so the chart fetch is gated on the range, never the per-tick state. Ported from the
 * web `EntityScreen`.
 */
@HiltViewModel
class EntityDetailViewModel @Inject constructor(
    private val connection: ConnectionManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val entityId: String = savedStateHandle.get<String>("entityId").orEmpty()

    private val state = connection.state

    val device: StateFlow<DeviceUi?> =
        state.entities.map { entities ->
            entities[entityId]?.let { e ->
                DeviceUi(
                    entityId = e.entityId,
                    name = resolveName(e, overrides),
                    stateText = e.state.replaceFirstChar { c -> c.uppercaseChar() },
                    rawState = e.state,
                    card = domainToCard(e.entityId),
                    attributes = e.attributes,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The hidden config/diagnostic entities belonging to the same device as this entity (battery,
     * last activity, volume, siren, motion-detection toggle…). They're filtered out of the main
     * Devices list, so this entity's detail doubles as the device view that keeps them reachable.
     */
    val diagnostics: StateFlow<List<DeviceUi>> =
        combine(state.entities, state.devices, state.entityCategories) { entities, index, categories ->
            val deviceId = index.deviceByEntity[entityId] ?: return@combine emptyList()
            val record = index.devices[deviceId] ?: return@combine emptyList()
            record.entityIds
                .filter { it != entityId && it in categories }
                .mapNotNull { entities[it] }
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

    private val _hours = MutableStateFlow(24)
    val hours: StateFlow<Int> = _hours.asStateFlow()

    private val _history = MutableStateFlow<HistoryUi>(HistoryUi.Loading)
    val history: StateFlow<HistoryUi> = _history.asStateFlow()

    init {
        // collectLatest cancels an in-flight fetch when the range changes (the web `active` flag).
        viewModelScope.launch {
            _hours.collectLatest { h -> loadHistory(h) }
        }
    }

    fun setHours(h: Int) { _hours.value = h }

    private suspend fun loadHistory(h: Int) {
        _history.value = HistoryUi.Loading
        _history.value = try {
            val levels = historyLevels(connection.fetchHistory(entityId, h))
            if (levels.size < 2) HistoryUi.Empty else HistoryUi.Data(levels)
        } catch (_: Exception) {
            HistoryUi.Error
        }
    }

    /** Non-optimistic control call; the store reconciles from the source echo. */
    fun call(service: String, extra: Map<String, Any?> = emptyMap()) {
        viewModelScope.launch {
            connection.callService(domainOf(entityId), service, ServiceData(entityId = entityId, extra = extra))
        }
    }
}

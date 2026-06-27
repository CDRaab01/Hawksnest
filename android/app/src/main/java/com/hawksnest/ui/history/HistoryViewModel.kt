package com.hawksnest.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.logic.LogEvent
import com.hawksnest.core.logic.isPrimaryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** History feed state for the activity timeline. */
sealed interface HistoryFeed {
    data object Loading : HistoryFeed
    data object Error : HistoryFeed
    data class Loaded(val events: List<LogEvent>) : HistoryFeed
}

/**
 * History hub — a filterable activity timeline over HA's logbook. Refetches on range change and
 * when the source goes live/ready (mirrors the web `HistoryScreen` effect on `[range, status]`).
 * The category filter is applied in the screen so changing it doesn't refetch.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val _hours = MutableStateFlow(24)
    val hours: StateFlow<Int> = _hours.asStateFlow()

    private val _domain = MutableStateFlow("all")
    val domain: StateFlow<String> = _domain.asStateFlow()

    private val _feed = MutableStateFlow<HistoryFeed>(HistoryFeed.Loading)
    val feed: StateFlow<HistoryFeed> = _feed.asStateFlow()

    init {
        viewModelScope.launch {
            // Refetch when the range changes OR the connection status flips (e.g. demo → live).
            combine(_hours, connection.state.status) { h, _ -> h }
                .collectLatest { h -> load(h) }
        }
    }

    fun setHours(h: Int) { _hours.value = h }
    fun setDomain(d: String) { _domain.value = d }

    private suspend fun load(hours: Int) {
        _feed.value = HistoryFeed.Loading
        _feed.value = try {
            val end = System.currentTimeMillis()
            val start = end - hours * 3_600_000L
            // Drop config/diagnostic + ring-mqtt housekeeping entities (the `sensor.*_last_activity`,
            // `*_info`, `*_battery`, … spam) so the timeline shows meaningful state changes, not
            // noise. `*_last_activity` is untagged by ring-mqtt, so a category-only check isn't enough.
            val categories = connection.state.entityCategories.value
            val events = connection.fetchLogbook(start, end)
                .filter { it.entityId == null || isPrimaryEntity(it.entityId!!, categories) }
            HistoryFeed.Loaded(events)
        } catch (_: Exception) {
            HistoryFeed.Error
        }
    }
}

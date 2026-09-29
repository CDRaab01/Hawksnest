package com.hawksnest.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.LOGBOOK_MAX_EVENTS
import com.hawksnest.core.logic.LogEvent
import com.hawksnest.core.logic.capLogbook
import com.hawksnest.core.logic.describeStateChange
import com.hawksnest.core.logic.displayName
import com.hawksnest.core.logic.isPrimaryEntity
import com.hawksnest.core.logic.prettifyEntityId
import com.hawksnest.util.DevicePrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** History feed state for the activity timeline. */
sealed interface HistoryFeed {
    data object Loading : HistoryFeed
    data object Error : HistoryFeed

    /**
     * [events] are the chosen category's newest, capped; [truncated] when that category held more
     * than [LOGBOOK_MAX_EVENTS]. [domains] are every category in the window, so the chips stay put
     * while one is selected.
     */
    data class Loaded(
        val events: List<LogEvent>,
        val truncated: Boolean = false,
        val domains: List<String> = emptyList(),
    ) : HistoryFeed
}

/** What was fetched for the current range, before the category filter and the cap. */
private sealed interface Window {
    data object Loading : Window
    data object Error : Window
    data class Loaded(val events: List<LogEvent>) : Window
}

// Float the most useful event domains to the front of the chip row.
private val DOMAIN_ORDER = listOf("camera", "binary_sensor", "lock", "alarm_control_panel", "light")

/**
 * History hub — a filterable activity timeline over HA's logbook. Refetches on range change and
 * when the source goes live/ready (mirrors the web `HistoryScreen` effect on `[range, status]`).
 *
 * The category filter runs HERE, before the cap. It used to run in the screen over the 500 already
 * kept, so a quiet category (locks) could read "No events" while HA held plenty of them.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val connection: ConnectionManager,
    private val devicePrefs: DevicePrefsStore,
) : ViewModel() {

    private val _hours = MutableStateFlow(24)
    val hours: StateFlow<Int> = _hours.asStateFlow()

    private val _domain = MutableStateFlow("all")
    val domain: StateFlow<String> = _domain.asStateFlow()

    private val window = MutableStateFlow<Window>(Window.Loading)

    val feed: StateFlow<HistoryFeed> = combine(window, _domain) { w, d ->
        when (w) {
            Window.Loading -> HistoryFeed.Loading
            Window.Error -> HistoryFeed.Error
            is Window.Loaded -> {
                val chosen = if (d == "all") w.events else w.events.filter { it.domain == d }
                val capped = capLogbook(chosen)
                HistoryFeed.Loaded(capped.events, capped.truncated, presentDomains(w.events))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryFeed.Loading)

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
        window.value = Window.Loading
        window.value = try {
            val end = System.currentTimeMillis()
            val start = end - hours * 3_600_000L
            // Drop config/diagnostic + ring-mqtt housekeeping entities (the `sensor.*_last_activity`,
            // `*_info`, `*_battery`, … spam) so the timeline shows meaningful state changes, not
            // noise. `*_last_activity` is untagged by ring-mqtt, so a category-only check isn't enough.
            val categories = connection.state.entityCategories.value
            val events = connection.fetchLogbook(start, end)
                .filter { it.entityId == null || isPrimaryEntity(it.entityId!!, categories) }
            Window.Loaded(humanize(events))
        } catch (_: Exception) {
            // Note this cannot catch an OutOfMemoryError — that is an Error, not an Exception, and
            // is exactly how the unbounded version took the app down rather than showing this.
            Window.Error
        }
    }

    /**
     * Name each row the way the rest of the app names the device (renames, overrides, device
     * names), and word a bare state change for what the device is. HA's own message, when it sent
     * one (an automation's "triggered by …"), is kept as is.
     */
    private suspend fun humanize(events: List<LogEvent>): List<LogEvent> {
        val entities = connection.state.entities.value
        val devices = connection.state.devices.value
        val renames = devicePrefs.renames.first()
        return events.map { ev ->
            val id = ev.entityId ?: return@map ev
            val entity = entities[id]
            val deviceName = devices.deviceByEntity[id]?.let { devices.devices[it]?.name }
            val name = entity?.let { displayName(it, overrides, renames[id], deviceName) }
                ?: ev.name.takeIf { it != id }
                ?: prettifyEntityId(id)
            val message = if (ev.hasHaMessage) {
                ev.message
            } else {
                describeStateChange(
                    ev.domain,
                    entity?.stringAttr("device_class"),
                    ev.state,
                    entity?.stringAttr("unit_of_measurement"),
                ) ?: ev.state?.let { "Changed to $it" } ?: ev.message
            }
            ev.copy(name = name, message = message)
        }
    }
}

private fun presentDomains(events: List<LogEvent>): List<String> {
    val seen = events.mapNotNull { it.domain }.toSet()
    return seen.sortedWith(
        compareBy({ DOMAIN_ORDER.indexOf(it).let { i -> if (i == -1) 99 else i } }, { it }),
    )
}

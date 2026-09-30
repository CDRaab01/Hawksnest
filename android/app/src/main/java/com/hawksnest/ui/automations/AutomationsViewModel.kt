package com.hawksnest.ui.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.automations.automationDescription
import com.hawksnest.core.automations.describeTriggers
import com.hawksnest.core.automations.groupOrder
import com.hawksnest.core.automations.ranToday
import com.hawksnest.core.automations.titleAutomation
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.devices.controlLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject

/** One automation, as the list and its detail sheet show it. */
data class AutomationUi(
    val entityId: String,
    /** The full name, as HA has it: search matches it, and the sheet shows it. */
    val name: String,
    val group: String,
    val title: String,
    val subtitle: String?,
    /** Deployed by hawksnest-automation to keep the house running: no Run. */
    val system: Boolean,
    val enabled: Boolean,
    val lastRanMs: Long?,
    /** HA Config-API id (from the entity's `id` attribute); null → not editable in Hawksnest. */
    val configId: String?,
)

/** A room's worth of automations, with what its closed header says. */
data class AutomationGroupUi(
    val name: String,
    val items: List<AutomationUi>,
    val off: Int,
)

enum class AutomationFilter(val label: String) {
    ALL("All"),
    NEEDS_A_LOOK("Needs a look"),
    RAN_TODAY("Ran today"),
    OFF("Off"),
}

data class AutomationsUi(
    val groups: List<AutomationGroupUi> = emptyList(),
    val total: Int = 0,
    val on: Int = 0,
    val ranToday: Int = 0,
    /** Off, or never run: the two states worth a look. */
    val needsALook: Int = 0,
    /** A filter or search is narrowing the list, so matching groups open by themselves. */
    val narrowed: Boolean = false,
)

/** What the detail sheet adds from the automation's own config. */
data class AutomationDetail(val whenText: String?, val description: String?)

/**
 * Automations — HA's automations surfaced as `automation.*` entities, grouped by room (see
 * `core/automations/AutomationGroups.kt`) so the list reads as a handful of rooms rather than 36
 * truncated rows. HA runs them; Hawksnest lists, toggles (turn_on/turn_off), runs (trigger) and
 * edits them. The web `AutomationsScreen` is still the flat list.
 */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    private val _filter = MutableStateFlow(AutomationFilter.ALL)
    val filter: StateFlow<AutomationFilter> = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setFilter(f: AutomationFilter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }

    private val all: StateFlow<List<AutomationUi>> =
        combine(state.entities, state.areas) { entities, areas ->
            val areaNames = areas.values.toSet()
            entities.values
                .filter { domainOf(it.entityId) == "automation" }
                .map { e -> toUi(e, areaNames, areas[e.entityId]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ui: StateFlow<AutomationsUi> = combine(all, _filter, _query) { items, filter, query ->
        val now = System.currentTimeMillis()
        val q = query.trim()
        val shown = items.filter { a ->
            val passes = when (filter) {
                AutomationFilter.ALL -> true
                AutomationFilter.NEEDS_A_LOOK -> !a.enabled || a.lastRanMs == null
                AutomationFilter.RAN_TODAY -> ranToday(a.lastRanMs, now)
                AutomationFilter.OFF -> !a.enabled
            }
            passes && (q.isEmpty() || a.name.contains(q, ignoreCase = true) || a.group.contains(q, ignoreCase = true))
        }
        AutomationsUi(
            groups = shown.groupBy { it.group }
                .map { (name, list) ->
                    AutomationGroupUi(
                        name = name,
                        items = list.sortedWith(compareBy({ it.title.lowercase() }, { it.subtitle?.lowercase() })),
                        off = list.count { !it.enabled },
                    )
                }
                .sortedWith(compareBy({ groupOrder(it.name).first }, { groupOrder(it.name).second })),
            total = items.size,
            on = items.count { it.enabled },
            ranToday = items.count { ranToday(it.lastRanMs, now) },
            needsALook = items.count { !it.enabled || it.lastRanMs == null },
            narrowed = filter != AutomationFilter.ALL || q.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationsUi())

    val isDemo: StateFlow<Boolean> =
        state.status.map { it == ConnectionStatus.DEMO }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Entity ids with a control in flight: the sheet's Run shows it's running until HA echoes. */
    val pending: StateFlow<Set<String>> = connection.pendingControls

    /** Enable/disable the automation — crash-safe via the control gate (the entity echo flips
     *  the switch; failures land on the app snackbar). */
    fun setEnabled(entityId: String, desired: Boolean) {
        connection.control(
            entityId,
            if (desired) "turn_on" else "turn_off",
            label = controlLabel(connection, entityId),
        )
    }

    /** Run the automation now (HA's `automation.trigger`), crash-safe. The `last_triggered`
     *  attribute updating is the echo that clears pending. */
    fun run(entityId: String) {
        connection.control(entityId, "trigger", label = controlLabel(connection, entityId))
    }

    /**
     * When it fires and what it does, from the automation's HA config. Null parts when it can't be
     * read (no config id, demo, an error): the sheet just leaves those sections out.
     */
    suspend fun detail(configId: String?): AutomationDetail {
        val config = configId?.let { runCatching { connection.getAutomationConfig(it) }.getOrNull() }
            ?: return AutomationDetail(null, null)
        val entities = state.entities.value
        return AutomationDetail(
            whenText = describeTriggers(
                config,
                nameOf = { id -> entities[id]?.let { resolveName(it, overrides) } ?: id },
                deviceClassOf = { id -> entities[id]?.stringAttr("device_class") },
            ),
            description = automationDescription(config),
        )
    }

    private fun toUi(e: HassEntity, areaNames: Set<String>, assignedArea: String?): AutomationUi {
        val name = resolveName(e, overrides)
        val t = titleAutomation(e.stringAttr("friendly_name") ?: name, areaNames, assignedArea)
        return AutomationUi(
            entityId = e.entityId,
            name = name,
            group = t.group,
            title = t.title,
            subtitle = t.subtitle,
            system = t.system,
            enabled = e.state == "on",
            lastRanMs = lastTriggeredMs(e),
            configId = e.stringAttr("id"),
        )
    }
}

private fun lastTriggeredMs(e: HassEntity): Long? {
    val raw = e.stringAttr("last_triggered") ?: return null
    return runCatching { Instant.parse(raw).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrNull()
}

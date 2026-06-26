package com.hawksnest.ui.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.ConnectionStatus
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.resolveName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** One automation row for the list. */
data class AutomationUi(
    val entityId: String,
    val name: String,
    val enabled: Boolean,
    val lastTriggered: String,
)

/**
 * Automations — HA's automations surfaced as `automation.*` entities. HA runs them; Hawksnest lists,
 * toggles (turn_on/turn_off), and runs (trigger) them, all non-optimistically through the live
 * source. The Config-API editor is deferred — this is the read + enable/run surface. Ported from the
 * web `AutomationsScreen`.
 */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    private val state = connection.state

    val automations: StateFlow<List<AutomationUi>> =
        state.entities.map { entities ->
            entities.values
                .filter { domainOf(it.entityId) == "automation" }
                .map { e ->
                    AutomationUi(
                        entityId = e.entityId,
                        name = resolveName(e, overrides),
                        enabled = e.state == "on",
                        lastTriggered = lastTriggeredLabel(e),
                    )
                }
                .sortedBy { it.name.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isDemo: StateFlow<Boolean> =
        state.status.map { it == ConnectionStatus.DEMO }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Enable/disable the automation (non-optimistic — the entity echo flips the switch). */
    fun setEnabled(entityId: String, desired: Boolean) {
        viewModelScope.launch {
            connection.callService(
                "automation",
                if (desired) "turn_on" else "turn_off",
                ServiceData(entityId = entityId),
            )
        }
    }

    /** Run the automation now (HA's `automation.trigger`). */
    fun run(entityId: String) {
        viewModelScope.launch {
            connection.callService("automation", "trigger", ServiceData(entityId = entityId))
        }
    }
}

private val LAST_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a")

private fun lastTriggeredLabel(e: HassEntity): String {
    val raw = e.stringAttr("last_triggered") ?: return "Hasn't run yet"
    val inst = runCatching { Instant.parse(raw) }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
        .getOrNull() ?: return "Hasn't run yet"
    return "Last run " + inst.atZone(ZoneId.systemDefault()).format(LAST_FMT)
}

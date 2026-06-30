package com.hawksnest.ui.automations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.config.overrides
import com.hawksnest.core.automations.Rule
import com.hawksnest.core.automations.RuleTrigger
import com.hawksnest.core.automations.configToRule
import com.hawksnest.core.automations.newRule
import com.hawksnest.core.automations.ruleToConfig
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.primaryEntities
import com.hawksnest.core.logic.resolveName
import com.hawksnest.util.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

/** A device option for a picker — `entityId` value, friendly `label`. */
data class DeviceOption(val entityId: String, val label: String)

/** The editor's load/edit state. */
sealed interface EditState {
    data object Loading : EditState
    data class Editing(val rule: Rule, val isNew: Boolean) : EditState
    /** The automation uses features Hawksnest can't model — offer "edit in Home Assistant". */
    data class Unsupported(val alias: String) : EditState
    data class Failed(val message: String) : EditState
}

/**
 * Backs the automation builder. Loads an existing config (→ [configToRule]) into an editable [Rule],
 * or starts a fresh one; converts the [Rule] back to an HA config ([ruleToConfig]) and writes it via
 * the Config API on save. A config outside the V1 subset shows the read-only "edit in HA" fallback.
 */
@HiltViewModel
class AutomationEditViewModel @Inject constructor(
    private val connection: ConnectionManager,
    private val credentialStore: CredentialStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** "new" for a fresh automation, or an existing HA config id. */
    val configId: String = savedStateHandle["id"] ?: "new"
    val isNew: Boolean = configId == "new"

    private val _state = MutableStateFlow<EditState>(
        if (isNew) EditState.Editing(newRule(), isNew = true) else EditState.Loading,
    )
    val state: StateFlow<EditState> = _state.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Flips to true once a save/delete succeeds — the screen pops back. */
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** The connected HA origin, for the "edit in Home Assistant" deep link (null in demo). */
    var haUrl: String? = null
        private set

    /**
     * Every primary entity, friendly-named and sorted — the source for the builder's pickers.
     * Drops HA config/diagnostic + ring-mqtt housekeeping entities so the picker lists real
     * controls/signals, not "Back Battery / Back Door Info / Back Event Stream / …" noise.
     */
    val entities: StateFlow<List<HassEntity>> =
        combine(connection.state.entities, connection.state.entityCategories) { map, categories ->
            primaryEntities(map.values.toList(), categories)
                .sortedBy { resolveName(it, overrides).lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { haUrl = credentialStore.haUrl.firstOrNull() }
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.value = EditState.Loading
            val config = runCatching { connection.getAutomationConfig(configId) }.getOrElse {
                _state.value = EditState.Failed(it.message ?: "Couldn't load automation.")
                return@launch
            }
            if (config == null) {
                _state.value = EditState.Failed("That automation no longer exists.")
                return@launch
            }
            val rule = configToRule(config)
            _state.value = if (rule != null) {
                EditState.Editing(rule, isNew = false)
            } else {
                val alias = (config["alias"] as? JsonPrimitive)?.contentOrNull ?: configId
                EditState.Unsupported(alias)
            }
        }
    }

    /** Replace the working draft (the screen drives every field edit through here). */
    fun update(rule: Rule) {
        val s = _state.value
        if (s is EditState.Editing) _state.value = s.copy(rule = rule)
    }

    fun save() {
        val rule = (_state.value as? EditState.Editing)?.rule ?: return
        _saveError.value = null
        validate(rule)?.let { _saveError.value = it; return }
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                connection.saveAutomationConfig(ruleToConfig(rule.copy(alias = rule.alias.trim())))
            }
            if (result.isSuccess) {
                // The Config REST POST returns on 2xx, but the new automation.* entity only
                // reaches state.entities later, over the subscribe_entities WebSocket. Wait for
                // that echo (staying busy) so the list shows the new row on return instead of
                // looking like nothing happened. Times out gracefully so a slow/edge-case HA
                // can never trap the user (worst case: pops after 5s, today's behaviour).
                withTimeoutOrNull(5_000) {
                    connection.state.entities.first { entities ->
                        entities.values.any { it.stringAttr("id") == rule.id }
                    }
                }
                _done.value = true
            } else {
                _saveError.value = result.exceptionOrNull()?.message ?: "Couldn't save the automation."
                _busy.value = false
            }
        }
    }

    fun delete() {
        if (isNew) return
        _saveError.value = null
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching { connection.deleteAutomationConfig(configId) }
            if (result.isSuccess) {
                _done.value = true
            } else {
                _saveError.value = result.exceptionOrNull()?.message ?: "Couldn't delete the automation."
                _busy.value = false
            }
        }
    }

    /** Null when the draft is savable, else the first problem to show the user. */
    private fun validate(rule: Rule): String? {
        if (rule.alias.isBlank()) return "Give the automation a name."
        when (val t = rule.trigger) {
            is RuleTrigger.State ->
                if (t.entityId.isEmpty() || t.to.isEmpty()) {
                    return "Pick a trigger device and the state that should fire it."
                }
            is RuleTrigger.Time ->
                if (t.at.isEmpty()) return "Pick the time the automation should fire."
            is RuleTrigger.Presence ->
                if (t.personEntityId.isEmpty()) {
                    return "Pick the person whose arrival or departure fires this."
                }
            is RuleTrigger.Sun -> Unit
        }
        if (rule.actions.isEmpty() || rule.actions.any { it.targetEntityIds.isEmpty() }) {
            return "Every action needs at least one target device."
        }
        return null
    }
}

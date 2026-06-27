package com.hawksnest.ui.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.config.overrides
import com.hawksnest.core.automations.ACTION_DOMAINS
import com.hawksnest.core.automations.DOMAIN_LABEL
import com.hawksnest.core.automations.PRESENCE_DOMAINS
import com.hawksnest.core.automations.PRESENCE_EVENTS
import com.hawksnest.core.automations.PresenceEvent
import com.hawksnest.core.automations.Rule
import com.hawksnest.core.automations.RuleAction
import com.hawksnest.core.automations.RuleCondition
import com.hawksnest.core.automations.RuleTrigger
import com.hawksnest.core.automations.SUN_EVENTS
import com.hawksnest.core.automations.SunEvent
import com.hawksnest.core.automations.TRIGGER_TYPES
import com.hawksnest.core.automations.TriggerKind
import com.hawksnest.core.automations.kind
import com.hawksnest.core.automations.newTriggerOfKind
import com.hawksnest.core.automations.stateOptionsFor
import com.hawksnest.core.automations.verbsFor
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.resolveName
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/** A picker option — stored `value`, displayed `label`. */
private data class Option(val value: String, val label: String)

/**
 * Automation builder — the IFTTT-style "if this, then that" editor. A trigger-type chooser drives
 * per-type fields (device/state, time, sun, presence), optional conditions, and one+ actions. Saves
 * via [AutomationEditViewModel] to HA's Config API; an unsupported config falls back to "edit in HA".
 */
@Composable
fun AutomationEditScreen(
    onBack: () -> Unit,
    viewModel: AutomationEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val done by viewModel.done.collectAsState()
    val entities by viewModel.entities.collectAsState()

    if (done) {
        // Save/delete succeeded — leave the editor.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = HawksnestTheme.spacing.xl),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.sm, vertical = HawksnestTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (viewModel.isNew) "New automation" else "Edit automation",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = HawksnestTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.lg),
        ) {
            when (val s = state) {
                is EditState.Loading ->
                    PanelCard { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                is EditState.Failed ->
                    PanelCard(channel = HawksnestTheme.pulse.streak) {
                        Text(s.message, color = HawksnestTheme.pulse.streak)
                    }

                is EditState.Unsupported -> UnsupportedCard(s.alias, viewModel.haUrl, viewModel.configId)

                is EditState.Editing -> {
                    EditorForm(
                        rule = s.rule,
                        entities = entities,
                        onChange = viewModel::update,
                    )

                    saveError?.let {
                        PanelCard(channel = HawksnestTheme.pulse.streak) {
                            Text(it, color = HawksnestTheme.pulse.streak)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
                        PulseButton(
                            text = if (viewModel.isNew) "Create" else "Save",
                            onClick = viewModel::save,
                            enabled = !busy,
                        )
                        if (!viewModel.isNew) {
                            PulseButton(
                                text = "Delete",
                                onClick = viewModel::delete,
                                enabled = !busy,
                                tonal = true,
                                channel = HawksnestTheme.pulse.streak,
                                dimChannel = HawksnestTheme.pulse.streakDim,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnsupportedCard(alias: String, haUrl: String?, id: String) {
    val uriHandler = LocalUriHandler.current
    SectionHeader("Edit in Home Assistant", channel = HawksnestTheme.pulse.streak)
    PanelCard {
        Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
            Text(alias, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "This automation uses features Hawksnest can't edit yet (for example multiple " +
                    "triggers, templates, or services it doesn't model). It still runs normally — " +
                    "edit it in Home Assistant to avoid losing detail.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!haUrl.isNullOrBlank()) {
                Row(
                    modifier = Modifier.clickable { uriHandler.openUri("$haUrl/config/automation/edit/$id") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
                ) {
                    Text("Open in Home Assistant", color = HawksnestTheme.pulse.effort)
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = HawksnestTheme.pulse.effort)
                }
            }
        }
    }
}

@Composable
private fun EditorForm(
    rule: Rule,
    entities: List<HassEntity>,
    onChange: (Rule) -> Unit,
) {
    val deviceOptions = remember(entities) {
        entities.map { Option(it.entityId, resolveName(it, overrides)) }
    }

    // --- Name ---------------------------------------------------------------
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        SectionHeader("Name", channel = HawksnestTheme.pulse.effort)
        OutlinedTextField(
            value = rule.alias,
            onValueChange = { onChange(rule.copy(alias = it)) },
            placeholder = { Text("e.g. Lock all doors when armed") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // --- Trigger ------------------------------------------------------------
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        SectionHeader("When this happens", channel = HawksnestTheme.pulse.effort)
        PanelCard {
            Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs)) {
                    TRIGGER_TYPES.forEach { tt ->
                        FilterChip(
                            selected = rule.trigger.kind == tt.kind,
                            onClick = {
                                if (rule.trigger.kind != tt.kind) {
                                    onChange(rule.copy(trigger = newTriggerOfKind(tt.kind)))
                                }
                            },
                            label = { Text(tt.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                TriggerFields(rule.trigger, deviceOptions, onChange = { onChange(rule.copy(trigger = it)) })
            }
        }
    }

    // --- Conditions (optional) ---------------------------------------------
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        SectionHeader(
            "Only if (optional)",
            channel = HawksnestTheme.pulse.recovery,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs)) {
                    PulseButton(
                        text = "Device",
                        onClick = { onChange(rule.copy(conditions = rule.conditions + RuleCondition.StateIs("", ""))) },
                        tonal = true,
                        compact = true,
                    )
                    PulseButton(
                        text = "Time",
                        onClick = { onChange(rule.copy(conditions = rule.conditions + RuleCondition.TimeWindow())) },
                        tonal = true,
                        compact = true,
                    )
                }
            },
        )
        if (rule.conditions.isEmpty()) {
            PanelCard {
                Text(
                    "No conditions — the actions run every time the trigger fires.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            rule.conditions.forEachIndexed { i, c ->
                PanelCard {
                    ConditionFields(
                        condition = c,
                        deviceOptions = deviceOptions,
                        onChange = { updated ->
                            onChange(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == i) updated else x }))
                        },
                        onRemove = {
                            onChange(rule.copy(conditions = rule.conditions.filterIndexed { j, _ -> j != i }))
                        },
                    )
                }
            }
        }
    }

    // --- Actions ------------------------------------------------------------
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        SectionHeader(
            "Do this",
            channel = HawksnestTheme.pulse.strength,
            trailing = {
                PulseButton(
                    text = "Add",
                    onClick = {
                        onChange(rule.copy(actions = rule.actions + RuleAction("lock", "lock", emptyList())))
                    },
                    tonal = true,
                    compact = true,
                )
            },
        )
        rule.actions.forEachIndexed { i, a ->
            PanelCard {
                ActionFields(
                    action = a,
                    entities = entities,
                    canRemove = rule.actions.size > 1,
                    onChange = { updated ->
                        onChange(rule.copy(actions = rule.actions.mapIndexed { j, x -> if (j == i) updated else x }))
                    },
                    onRemove = {
                        onChange(rule.copy(actions = rule.actions.filterIndexed { j, _ -> j != i }))
                    },
                )
            }
        }
    }
}

@Composable
private fun TriggerFields(
    trigger: RuleTrigger,
    deviceOptions: List<Option>,
    onChange: (RuleTrigger) -> Unit,
) {
    when (trigger) {
        is RuleTrigger.State -> {
            val domain = if (trigger.entityId.isNotEmpty()) domainOf(trigger.entityId) else ""
            Dropdown("Device", labelFor(deviceOptions, trigger.entityId), deviceOptions) {
                onChange(RuleTrigger.State(entityId = it, to = ""))
            }
            StatePicker("Reaches state", domain, trigger.to) { onChange(trigger.copy(to = it)) }
        }
        is RuleTrigger.Time -> {
            FieldLabel("At time (24h, HH:MM)")
            OutlinedTextField(
                value = trigger.at,
                onValueChange = { onChange(RuleTrigger.Time(it.filter { c -> c.isDigit() || c == ':' })) },
                placeholder = { Text("e.g. 22:30") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is RuleTrigger.Sun -> {
            val sunOptions = SUN_EVENTS.map { Option(it.value, it.label) }
            Dropdown("Event", trigger.event.value.replaceFirstChar { it.uppercase() }, sunOptions) {
                onChange(trigger.copy(event = if (it == "sunrise") SunEvent.SUNRISE else SunEvent.SUNSET))
            }
            FieldLabel("Offset (minutes, − before / + after)")
            OutlinedTextField(
                value = trigger.offsetMinutes.toString(),
                onValueChange = { raw ->
                    val cleaned = raw.filterIndexed { idx, c -> c.isDigit() || (c == '-' && idx == 0) }
                    onChange(trigger.copy(offsetMinutes = cleaned.toIntOrNull() ?: 0))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is RuleTrigger.Presence -> {
            val people = deviceOptions.filter { domainOf(it.value) in PRESENCE_DOMAINS }
            Dropdown("Person", labelFor(deviceOptions, trigger.personEntityId), people) {
                onChange(trigger.copy(personEntityId = it))
            }
            if (people.isEmpty()) {
                Text(
                    "No people or device trackers found in Home Assistant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val eventOptions = PRESENCE_EVENTS.map { Option(it.value, it.label) }
            val selectedLabel = PRESENCE_EVENTS.firstOrNull { it.value == trigger.event.value }?.label ?: ""
            Dropdown("When they", selectedLabel, eventOptions) {
                onChange(trigger.copy(event = if (it == "enter") PresenceEvent.ENTER else PresenceEvent.LEAVE))
            }
        }
    }
}

@Composable
private fun ConditionFields(
    condition: RuleCondition,
    deviceOptions: List<Option>,
    onChange: (RuleCondition) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (condition is RuleCondition.TimeWindow) "Within a time window" else "A device is in a state",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove condition", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (condition) {
            is RuleCondition.StateIs -> {
                val domain = if (condition.entityId.isNotEmpty()) domainOf(condition.entityId) else ""
                Dropdown("Device", labelFor(deviceOptions, condition.entityId), deviceOptions) {
                    onChange(RuleCondition.StateIs(entityId = it, state = ""))
                }
                StatePicker("Is in state", domain, condition.state) { onChange(condition.copy(state = it)) }
            }
            is RuleCondition.TimeWindow -> {
                FieldLabel("After (HH:MM)")
                OutlinedTextField(
                    value = condition.after ?: "",
                    onValueChange = { onChange(condition.copy(after = it.ifEmpty { null })) },
                    placeholder = { Text("e.g. 20:00") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel("Before (HH:MM)")
                OutlinedTextField(
                    value = condition.before ?: "",
                    onValueChange = { onChange(condition.copy(before = it.ifEmpty { null })) },
                    placeholder = { Text("e.g. 06:00") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ActionFields(
    action: RuleAction,
    entities: List<HassEntity>,
    canRemove: Boolean,
    onChange: (RuleAction) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                val domainOptions = ACTION_DOMAINS.map { Option(it, DOMAIN_LABEL[it] ?: it) }
                Dropdown("Device type", DOMAIN_LABEL[action.domain] ?: action.domain, domainOptions) { d ->
                    onChange(RuleAction(domain = d, verb = verbsFor(d).firstOrNull()?.verb ?: "", targetEntityIds = emptyList()))
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove action", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val verbOptions = verbsFor(action.domain).map { Option(it.verb, it.label) }
        val verbLabel = verbsFor(action.domain).firstOrNull { it.verb == action.verb }?.label ?: ""
        Dropdown("Action", verbLabel, verbOptions) { onChange(action.copy(verb = it)) }

        FieldLabel("Target devices")
        val targets = entities.filter { domainOf(it.entityId) == action.domain }
        if (targets.isEmpty()) {
            Text(
                "No ${DOMAIN_LABEL[action.domain] ?: action.domain} devices found.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            targets.forEach { e ->
                val checked = e.entityId in action.targetEntityIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = if (checked) action.targetEntityIds - e.entityId else action.targetEntityIds + e.entityId
                            onChange(action.copy(targetEntityIds = next))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(
                        resolveName(e, overrides),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = HawksnestTheme.spacing.sm),
                    )
                }
            }
        }
    }
}

/** A curated state dropdown for a domain, or a free-text field when the domain has none. */
@Composable
private fun StatePicker(label: String, domain: String, value: String, onChange: (String) -> Unit) {
    val options = stateOptionsFor(domain)
    if (options.isNotEmpty()) {
        val selectedLabel = options.firstOrNull { it.value == value }?.label ?: ""
        Dropdown(label, selectedLabel, options.map { Option(it.value, it.label) }, onChange)
    } else {
        FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("State, e.g. on") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A small caption label above a field. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A read-only field that opens a [DropdownMenu] of [options] on tap. */
@Composable
private fun Dropdown(
    label: String,
    selectedLabel: String,
    options: List<Option>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs)) {
        FieldLabel(label)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, HawksnestTheme.pulse.hairline, MaterialTheme.shapes.small)
                    .background(HawksnestTheme.pulse.panel, MaterialTheme.shapes.small)
                    .clickable { expanded = true }
                    .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLabel.ifEmpty { "Select…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedLabel.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.label) },
                        onClick = { onSelect(opt.value); expanded = false },
                    )
                }
            }
        }
    }
}

private fun labelFor(options: List<Option>, value: String): String =
    options.firstOrNull { it.value == value }?.label ?: ""

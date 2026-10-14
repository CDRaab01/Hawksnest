package com.hawksnest.ui.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.automations.ACTION_DOMAINS
import com.hawksnest.core.automations.DOMAIN_LABEL
import com.hawksnest.core.automations.DeviceKind
import com.hawksnest.core.automations.PRESENCE_EVENTS
import com.hawksnest.core.automations.PresenceEvent
import com.hawksnest.core.automations.Rule
import com.hawksnest.core.automations.RuleAction
import com.hawksnest.core.automations.RuleCondition
import com.hawksnest.core.automations.RuleTrigger
import com.hawksnest.core.automations.SUN_EVENTS
import com.hawksnest.core.automations.StateOption
import com.hawksnest.core.automations.SunEvent
import com.hawksnest.core.automations.TRIGGER_TYPES
import com.hawksnest.core.automations.TriggerKind
import com.hawksnest.core.automations.conditionChoices
import com.hawksnest.core.automations.kind
import com.hawksnest.core.automations.newTriggerOfKind
import com.hawksnest.core.automations.ruleSentence
import com.hawksnest.core.automations.triggerChoices
import com.hawksnest.core.automations.verbsFor
import com.hawksnest.core.ha.domainOf
import com.hawksnest.ui.components.ConfirmDialog
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Automation builder — "when this happens, only if that, do this", built to read as the sentence
 * it makes. The old form was three dropdowns deep, and its "Device" dropdown was one alphabetical
 * list of ~260 entities (backups, lock sub-sensors, other automations) with no rooms or search.
 *
 * Now: the sentence it's building sits on top and updates as you go; trigger types are four
 * tiles; a device is picked in a sheet with search, type chips and rooms (with how each device
 * reads right now), with the house's plumbing hidden until asked for; and what a device should do
 * is chips in its own words ("Opens" / "Closes"), not raw states. The saved Home Assistant config
 * is exactly what it was. Saves via [AutomationEditViewModel]; a config outside what this edits
 * falls back to "edit in HA".
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
    val pickables by viewModel.pickables.collectAsState()

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
                        pickables = pickables,
                        nameOf = viewModel::nameOf,
                        deviceClassOf = viewModel::deviceClassOf,
                        onChange = viewModel::update,
                    )

                    saveError?.let {
                        PanelCard(channel = HawksnestTheme.pulse.streak) {
                            Text(it, color = HawksnestTheme.pulse.streak)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
                        PulseButton(
                            // Reflect the save-in-flight wait (we now hold until the new
                            // automation echoes back over the WebSocket) so it doesn't look idle.
                            text = when {
                                busy && viewModel.isNew -> "Creating…"
                                busy -> "Saving…"
                                viewModel.isNew -> "Create"
                                else -> "Save"
                            },
                            onClick = viewModel::save,
                            enabled = !busy,
                        )
                        if (!viewModel.isNew) {
                            var confirmDelete by remember { mutableStateOf(false) }
                            if (confirmDelete) {
                                ConfirmDialog(
                                    title = "Delete “${s.rule.alias.ifBlank { "this automation" }}”?",
                                    text = "It's removed from Home Assistant and stops running. " +
                                        "There's no undo.",
                                    confirmLabel = "Delete",
                                    onConfirm = viewModel::delete,
                                    onDismiss = { confirmDelete = false },
                                )
                            }
                            PulseButton(
                                text = "Delete",
                                onClick = { confirmDelete = true },
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
    PanelCard(Modifier.fillMaxWidth()) {
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

/** Which picker is open, if any: the trigger device, a condition's device, or an action's targets. */
private sealed interface PickerFor {
    data object Trigger : PickerFor
    data object Person : PickerFor
    data class Condition(val index: Int) : PickerFor
    data class Targets(val index: Int) : PickerFor
}

@Composable
private fun EditorForm(
    rule: Rule,
    pickables: List<Pickable>,
    nameOf: (String) -> String,
    deviceClassOf: (String) -> String?,
    onChange: (Rule) -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    var picker by remember { mutableStateOf<PickerFor?>(null) }
    val sentence = ruleSentence(rule, nameOf, deviceClassOf)

    // --- The sentence it's building ---------------------------------------------
    Text(
        sentence,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(pulse.effort.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
            .border(1.dp, pulse.effort.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
            .padding(HawksnestTheme.spacing.md),
    )

    // --- When -----------------------------------------------------------------
    PanelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
            FieldLabel("When")
            TriggerTiles(rule.trigger.kind) { k ->
                if (rule.trigger.kind != k) onChange(rule.copy(trigger = newTriggerOfKind(k)))
            }
            when (val t = rule.trigger) {
                is RuleTrigger.State -> {
                    PickButton(
                        label = t.entityId.takeIf { it.isNotEmpty() }?.let(nameOf),
                        detail = pickables.firstOrNull { it.entityId == t.entityId }?.let(::roomAndKind),
                        placeholder = "Pick a device",
                        onClick = { picker = PickerFor.Trigger },
                    )
                    if (t.entityId.isNotEmpty()) {
                        StateChoice(
                            choices = triggerChoices(domainOf(t.entityId), deviceClassOf(t.entityId)),
                            value = t.to,
                            textLabel = "Becomes (state)",
                        ) { onChange(rule.copy(trigger = t.copy(to = it))) }
                    }
                }
                is RuleTrigger.Time -> TimeField("At", t.at.ifEmpty { null }, clearable = false) {
                    onChange(rule.copy(trigger = RuleTrigger.Time(it ?: "")))
                }
                is RuleTrigger.Sun -> {
                    Chips(
                        SUN_EVENTS,
                        t.event.value,
                    ) { onChange(rule.copy(trigger = t.copy(event = if (it == "sunrise") SunEvent.SUNRISE else SunEvent.SUNSET))) }
                    // An offset set elsewhere (say 45 min) keeps a chip of its own rather than vanishing.
                    Chips(
                        (SUN_OFFSETS + t.offsetMinutes).distinct().sorted()
                            .map { StateOption(it.toString(), offsetLabel(it, t.event)) },
                        t.offsetMinutes.toString(),
                    ) { onChange(rule.copy(trigger = t.copy(offsetMinutes = it.toInt()))) }
                }
                is RuleTrigger.Presence -> {
                    PickButton(
                        label = t.personEntityId.takeIf { it.isNotEmpty() }?.let(nameOf),
                        detail = null,
                        placeholder = "Pick a person",
                        onClick = { picker = PickerFor.Person },
                    )
                    Chips(
                        PRESENCE_EVENTS,
                        t.event.value,
                    ) { onChange(rule.copy(trigger = t.copy(event = if (it == "enter") PresenceEvent.ENTER else PresenceEvent.LEAVE))) }
                }
            }
        }
    }

    // --- Only if (optional) ----------------------------------------------------
    PanelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
            FieldLabel("Only if (optional)")
            rule.conditions.forEachIndexed { i, c ->
                fun set(updated: RuleCondition) =
                    onChange(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == i) updated else x }))
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                        when (c) {
                            is RuleCondition.StateIs -> {
                                PickButton(
                                    label = c.entityId.takeIf { it.isNotEmpty() }?.let(nameOf),
                                    detail = pickables.firstOrNull { it.entityId == c.entityId }?.let(::roomAndKind),
                                    placeholder = "Pick a device",
                                    onClick = { picker = PickerFor.Condition(i) },
                                )
                                if (c.entityId.isNotEmpty()) {
                                    StateChoice(
                                        choices = conditionChoices(domainOf(c.entityId), deviceClassOf(c.entityId)),
                                        value = c.state,
                                        textLabel = "Is (state)",
                                    ) { set(c.copy(state = it)) }
                                }
                            }
                            is RuleCondition.TimeWindow -> {
                                TimeField("After", c.after, clearable = true) { set(c.copy(after = it)) }
                                TimeField("Before", c.before, clearable = true) { set(c.copy(before = it)) }
                            }
                        }
                    }
                    IconButton(onClick = { onChange(rule.copy(conditions = rule.conditions.filterIndexed { j, _ -> j != i })) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove this condition", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                AddLink("+ A device") { onChange(rule.copy(conditions = rule.conditions + RuleCondition.StateIs("", ""))) }
                AddLink("+ A time window") { onChange(rule.copy(conditions = rule.conditions + RuleCondition.TimeWindow())) }
            }
        }
    }

    // --- Do this ---------------------------------------------------------------
    rule.actions.forEachIndexed { i, a ->
        fun set(updated: RuleAction) =
            onChange(rule.copy(actions = rule.actions.mapIndexed { j, x -> if (j == i) updated else x }))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(if (i == 0) "Do this" else "Then", Modifier.weight(1f))
                    if (rule.actions.size > 1) {
                        IconButton(onClick = { onChange(rule.copy(actions = rule.actions.filterIndexed { j, _ -> j != i })) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove this action", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
                ) {
                    ACTION_DOMAINS.forEach { d ->
                        FilterChip(
                            selected = a.domain == d,
                            onClick = {
                                if (a.domain != d) set(RuleAction(domain = d, verb = verbsFor(d).firstOrNull()?.verb ?: "", targetEntityIds = emptyList()))
                            },
                            label = { Text(DOMAIN_LABEL[d] ?: d) },
                            leadingIcon = { Icon(domainIcon(d), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                Chips(
                    verbsFor(a.domain).map { StateOption(it.verb, it.label.replace(" — ", " ")) },
                    a.verb,
                ) { set(a.copy(verb = it)) }
                PickButton(
                    label = when (a.targetEntityIds.size) {
                        0 -> null
                        1 -> nameOf(a.targetEntityIds[0])
                        else -> "${nameOf(a.targetEntityIds[0])} + ${a.targetEntityIds.size - 1} more"
                    },
                    detail = null,
                    placeholder = "Pick which ${(DOMAIN_LABEL[a.domain] ?: "devices").lowercase()}",
                    onClick = { picker = PickerFor.Targets(i) },
                )
            }
        }
    }
    AddLink("+ Add another action") {
        onChange(rule.copy(actions = rule.actions + RuleAction("light", "turn_on", emptyList())))
    }

    // --- Name ------------------------------------------------------------------
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs)) {
        FieldLabel("Name")
        OutlinedTextField(
            value = rule.alias,
            onValueChange = { onChange(rule.copy(alias = it)) },
            placeholder = { Text(sentence.removeSuffix("."), maxLines = 2) },
            supportingText = { Text("Leave blank to use the sentence above.") },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // --- The open picker ---------------------------------------------------------
    when (val p = picker) {
        null -> Unit
        PickerFor.Trigger -> DevicePickerSheet(
            title = "Which device?",
            candidates = pickables,
            selected = setOf((rule.trigger as? RuleTrigger.State)?.entityId.orEmpty()),
            multi = false,
            onDone = { ids ->
                val t = rule.trigger as? RuleTrigger.State
                ids.firstOrNull()?.let { id -> if (t?.entityId != id) onChange(rule.copy(trigger = RuleTrigger.State(entityId = id, to = ""))) }
                picker = null
            },
            onDismiss = { picker = null },
        )
        PickerFor.Person -> DevicePickerSheet(
            title = "Who?",
            candidates = pickables.filter { it.kind == DeviceKind.PEOPLE },
            selected = setOf((rule.trigger as? RuleTrigger.Presence)?.personEntityId.orEmpty()),
            multi = false,
            onDone = { ids ->
                val t = rule.trigger as? RuleTrigger.Presence
                ids.firstOrNull()?.let { id -> if (t != null) onChange(rule.copy(trigger = t.copy(personEntityId = id))) }
                picker = null
            },
            onDismiss = { picker = null },
        )
        is PickerFor.Condition -> DevicePickerSheet(
            title = "Only if which device?",
            candidates = pickables,
            selected = setOf((rule.conditions.getOrNull(p.index) as? RuleCondition.StateIs)?.entityId.orEmpty()),
            multi = false,
            onDone = { ids ->
                ids.firstOrNull()?.let { id ->
                    onChange(rule.copy(conditions = rule.conditions.mapIndexed { j, x -> if (j == p.index) RuleCondition.StateIs(id, "") else x }))
                }
                picker = null
            },
            onDismiss = { picker = null },
        )
        is PickerFor.Targets -> {
            val a = rule.actions.getOrNull(p.index)
            if (a == null) {
                picker = null
            } else {
                DevicePickerSheet(
                    title = "Which ${(DOMAIN_LABEL[a.domain] ?: "devices").lowercase()}?",
                    candidates = pickables.filter { domainOf(it.entityId) == a.domain },
                    selected = a.targetEntityIds.toSet(),
                    multi = true,
                    onDone = { ids ->
                        onChange(rule.copy(actions = rule.actions.mapIndexed { j, x -> if (j == p.index) x.copy(targetEntityIds = ids.toList()) else x }))
                        picker = null
                    },
                    onDismiss = { picker = null },
                )
            }
        }
    }
}

private fun roomAndKind(p: Pickable): String = listOfNotNull(p.room, p.state).joinToString(" · ")

/** Sun offsets offered as chips, in minutes. */
private val SUN_OFFSETS = listOf(-60, -30, 0, 30, 60)

private fun offsetLabel(minutes: Int, event: SunEvent): String = when {
    minutes < 0 -> "${-minutes} min before"
    minutes > 0 -> "${minutes} min after"
    else -> if (event == SunEvent.SUNRISE) "At sunrise" else "At sunset"
}

private fun triggerIcon(kind: TriggerKind): ImageVector = when (kind) {
    TriggerKind.STATE -> Icons.Filled.Sensors
    TriggerKind.TIME -> Icons.Filled.Schedule
    TriggerKind.SUN -> Icons.Filled.WbTwilight
    TriggerKind.PRESENCE -> Icons.Filled.Person
}

/** The four trigger types as a 2×2 of big tiles. They were chips, and the fourth was cut to "..". */
@Composable
private fun TriggerTiles(selected: TriggerKind, onSelect: (TriggerKind) -> Unit) {
    val pulse = HawksnestTheme.pulse
    Column(verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        TRIGGER_TYPES.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                row.forEach { tile ->
                    val on = tile.kind == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (on) pulse.effort.copy(alpha = 0.16f) else pulse.panelHigh, MaterialTheme.shapes.small)
                            .border(1.dp, if (on) pulse.effort else pulse.hairline, MaterialTheme.shapes.small)
                            .clickable { onSelect(tile.kind) }
                            .padding(HawksnestTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(triggerIcon(tile.kind), contentDescription = null, tint = if (on) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            tile.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** A field-like button that opens a picker: the chosen thing (and where it is), or what to pick. */
@Composable
private fun PickButton(label: String?, detail: String?, placeholder: String, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(pulse.panelHigh, MaterialTheme.shapes.small)
            .border(1.dp, pulse.hairline, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        Text(
            label ?: placeholder,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (label != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (label != null) MaterialTheme.colorScheme.onSurface else pulse.effort,
            modifier = Modifier.weight(1f),
        )
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Choices in the device's own words as chips; a text field for a device with no vocabulary. */
@Composable
private fun StateChoice(choices: List<StateOption>, value: String, textLabel: String, onChange: (String) -> Unit) {
    if (choices.isNotEmpty()) {
        Chips(choices, value, onChange)
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(textLabel) },
            placeholder = { Text("e.g. on") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<StateOption>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
    ) {
        options.forEach { o ->
            FilterChip(selected = o.value == selected, onClick = { onSelect(o.value) }, label = { Text(o.label) })
        }
    }
}

/**
 * A time as a button that opens the system time picker, instead of an "HH:MM" text field that took
 * "7pm" and saved nothing. [clearable] puts a Clear in the picker, to take an optional bound back
 * off (in the dialog, so the form doesn't grow a second ✕ beside the condition's own).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String?, clearable: Boolean, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            PickButton(label = value, detail = null, placeholder = "Pick a time", onClick = { open = true })
        }
    }
    if (open) {
        val (h, m) = value?.split(":")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (20 to 0)
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(state.hour, state.minute))
                    open = false
                }) { Text("Set") }
            },
            dismissButton = {
                Row {
                    if (clearable && value != null) {
                        TextButton(onClick = {
                            onChange(null)
                            open = false
                        }) { Text("Clear") }
                    }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
            text = { TimePicker(state = state) },
            containerColor = HawksnestTheme.pulse.panelHigh,
        )
    }
}

/**
 * Picking a device: search, type chips and rooms, each row saying how the device reads right now.
 * Kinds the editor has no words for (backups, battery levels, lock sub-sensors: most of the ~260
 * entities the old dropdown listed) stay hidden until "Show everything". [multi] picks several
 * (an action's targets), with Select all per room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(
    title: String,
    candidates: List<Pickable>,
    selected: Set<String>,
    multi: Boolean,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<DeviceKind?>(null) }
    var everything by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(selected.filter { it.isNotEmpty() }.toSet()) }

    val hasHidden = candidates.any { it.kind == DeviceKind.OTHER }
    val visible = candidates.filter { everything || it.kind != DeviceKind.OTHER || it.entityId in selected }
    val kinds = visible.map { it.kind }.distinct().sortedBy { it.ordinal }
    val q = query.trim()
    val shown = visible.filter { p ->
        (kind == null || p.kind == kind) &&
            (q.isEmpty() || p.name.contains(q, ignoreCase = true) || p.room?.contains(q, ignoreCase = true) == true)
    }
    val byRoom = shown.groupBy { it.room ?: "No room" }.toSortedMap(compareBy({ it == "No room" }, { it.lowercase() }))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = pulse.panelHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = HawksnestTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search devices or rooms") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (kinds.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
                ) {
                    FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("All") })
                    kinds.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text(k.label) })
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                byRoom.forEach { (room, items) ->
                    item(key = "room:$room") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = HawksnestTheme.spacing.md, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FieldLabel(room, Modifier.weight(1f))
                            if (multi && items.size > 1) {
                                val all = items.all { it.entityId in chosen }
                                Text(
                                    if (all) "Clear" else "Select all",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = pulse.effort,
                                    modifier = Modifier.clickable {
                                        chosen = if (all) chosen - items.map { it.entityId }.toSet() else chosen + items.map { it.entityId }
                                    },
                                )
                            }
                        }
                    }
                    items(items, key = { it.entityId }) { p ->
                        val isChosen = p.entityId in chosen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isChosen) pulse.effort.copy(alpha = 0.12f) else pulse.panel, MaterialTheme.shapes.small)
                                .clickable {
                                    if (multi) {
                                        chosen = if (isChosen) chosen - p.entityId else chosen + p.entityId
                                    } else {
                                        onDone(setOf(p.entityId))
                                    }
                                }
                                .padding(horizontal = HawksnestTheme.spacing.md, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
                        ) {
                            if (multi) Checkbox(checked = isChosen, onCheckedChange = null)
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(p.state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            "Nothing matches.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = HawksnestTheme.spacing.md),
                        )
                    }
                }
                if (hasHidden && !everything) {
                    item {
                        Text(
                            "Backups, diagnostics and other plumbing are hidden. Show everything",
                            style = MaterialTheme.typography.bodySmall,
                            color = pulse.effort,
                            modifier = Modifier.clickable { everything = true }.padding(vertical = HawksnestTheme.spacing.md),
                        )
                    }
                }
            }
            if (multi) {
                PulseButton(
                    text = if (chosen.isEmpty()) "Done" else "Done · ${chosen.size} chosen",
                    onClick = { onDone(chosen) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = HawksnestTheme.spacing.md),
                )
            }
        }
    }
}

private fun domainIcon(domain: String): ImageVector = when (domain) {
    "lock" -> Icons.Filled.Lock
    "light" -> Icons.Filled.Lightbulb
    "switch" -> Icons.Filled.ToggleOn
    "fan" -> Icons.Filled.Air
    "cover" -> Icons.Filled.Blinds
    "alarm_control_panel" -> Icons.Filled.Shield
    "scene" -> Icons.Filled.Palette
    else -> Icons.Filled.PlayArrow
}

@Composable
private fun AddLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = HawksnestTheme.pulse.effort,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

/** A small caption label above a field. */
@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

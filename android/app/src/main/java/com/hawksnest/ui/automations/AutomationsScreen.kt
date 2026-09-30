package com.hawksnest.ui.automations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.automations.SYSTEM_GROUP
import com.hawksnest.core.automations.shortLastRan
import com.hawksnest.ui.components.ConfirmDialog
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.PulseButton
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.rememberHaptics
import com.hawksnest.ui.components.rememberOptimisticOnOff
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Automations, grouped by room. 36 automations in one flat list, each row carrying Run, Edit and a
 * switch with its name cut to ~15 characters, read as five identical "Master Bedroom LE…" rows and
 * over a hundred things to tap. Now each room is a card that opens; a row is the name it was always
 * trying to show (device over what it does), when it last ran, and its switch; Run and Edit live in
 * the sheet a row opens. The background plumbing sits last, in "Behind the scenes".
 */
@Composable
fun AutomationsScreen(
    onNew: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val query by viewModel.query.collectAsState()
    val isDemo by viewModel.isDemo.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val pulse = HawksnestTheme.pulse
    // Which rooms are open, kept across tab switches. Closed by default: the screen opens as a
    // handful of rooms, not 36 rows.
    var open by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AutomationUi?>(null) }
    val now = System.currentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HawksnestTheme.spacing.lg, vertical = HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        SectionHeader(
            "Automations",
            channel = pulse.effort,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) viewModel.setQuery("")
                    }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Close search" else "Search automations",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PulseButton(text = "New", onClick = onNew, compact = true)
                }
            },
        )

        if (ui.total > 0) {
            Text(
                "${ui.total} automations · ${ui.on} on · ${ui.ranToday} ran today",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search by name or room") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
        ) {
            AutomationFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { viewModel.setFilter(f) },
                    label = {
                        Text(if (f == AutomationFilter.NEEDS_A_LOOK && ui.needsALook > 0) "${f.label} ${ui.needsALook}" else f.label)
                    },
                )
            }
        }

        if (isDemo) {
            Text(
                "Demo mode — automations are simulated locally. Connect Home Assistant in Settings " +
                    "to create ones that actually run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ui.groups.isEmpty()) {
            PanelCard {
                Text(
                    when {
                        ui.total == 0 -> "No automations yet. Tap New to link your devices — e.g. " +
                            "\"when the alarm is armed, lock every door\" or \"turn on the porch light at sunset.\""
                        else -> "Nothing matches."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ui.groups.forEach { g ->
            // A filter or search opens every group it leaves anything in.
            val expanded = ui.narrowed || g.name in open
            PanelCard(
                contentPadding = 0.dp,
                containerColor = if (g.name == SYSTEM_GROUP) pulse.panel else null,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !ui.narrowed) { open = if (g.name in open) open - g.name else open + g.name }
                        .padding(horizontal = HawksnestTheme.spacing.lg, vertical = HawksnestTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
                ) {
                    Text(
                        g.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (g.name == SYSTEM_GROUP) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${g.items.size} · " + if (g.off == 0) "all on" else "${g.off} off",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (g.off > 0) pulse.streak else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!ui.narrowed) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Close ${g.name}" else "Open ${g.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (expanded) {
                    g.items.forEach { a ->
                        HorizontalDivider(color = pulse.hairline)
                        AutomationRow(
                            a = a,
                            lastRan = shortLastRan(a.lastRanMs, now),
                            pending = a.entityId in pending,
                            onOpen = { selected = a },
                            onToggle = { viewModel.setEnabled(a.entityId, it) },
                        )
                    }
                }
            }
        }
    }

    selected?.let { chosen ->
        // Follow the live row, so the sheet's switch and "last ran" update while it's open.
        val live = ui.groups.flatMap { it.items }.firstOrNull { it.entityId == chosen.entityId } ?: chosen
        AutomationSheet(
            a = live,
            running = live.entityId in pending,
            viewModel = viewModel,
            onEdit = { id -> selected = null; onEdit(id) },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun AutomationRow(
    a: AutomationUi,
    lastRan: String,
    pending: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    val (shown, setTarget) = rememberOptimisticOnOff(a.enabled, pending = pending)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = HawksnestTheme.spacing.lg, end = HawksnestTheme.spacing.md, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                a.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            a.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            if (a.enabled) lastRan else "Off",
            style = MaterialTheme.typography.labelMedium,
            color = if (a.enabled) MaterialTheme.colorScheme.onSurfaceVariant else HawksnestTheme.pulse.streak,
        )
        Switch(
            checked = shown,
            onCheckedChange = { desired ->
                if (desired) haptics.toggleOn() else haptics.toggleOff()
                setTarget(desired)
                onToggle(desired)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = HawksnestTheme.pulse.effort),
        )
    }
}

/**
 * What a row opens: the automation in full — its whole name, when it fires and what it does (from
 * its own config), when it last ran — with the actions that used to crowd every row. Run asks
 * first: HA's trigger skips the automation's conditions, so "Run" on something like a garage
 * routine does its thing whatever the house is doing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationSheet(
    a: AutomationUi,
    running: Boolean,
    viewModel: AutomationsViewModel,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val detail by produceState<AutomationDetail?>(null, a.configId) { value = viewModel.detail(a.configId) }
    var confirmRun by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val (shown, setTarget) = rememberOptimisticOnOff(a.enabled, pending = running)

    if (confirmRun) {
        ConfirmDialog(
            title = "Run “${a.title}” now?",
            text = "It does what the automation does, right away, even if its usual conditions aren't met.",
            confirmLabel = "Run",
            onConfirm = { viewModel.run(a.entityId) },
            onDismiss = { confirmRun = false },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pulse.panelHigh) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = HawksnestTheme.spacing.lg, end = HawksnestTheme.spacing.lg, bottom = HawksnestTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SheetLabel(a.group)
                    Text(
                        a.title + (a.subtitle?.let { ": ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: ""),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(
                    checked = shown,
                    onCheckedChange = { desired ->
                        if (desired) haptics.toggleOn() else haptics.toggleOff()
                        setTarget(desired)
                        viewModel.setEnabled(a.entityId, desired)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
                )
            }

            val lastRan = a.lastRanMs?.let { "Last ran ${shortLastRan(it, System.currentTimeMillis()).let { s -> if (s.contains(':')) "today at $s" else s }}" }
                ?: "Hasn't run yet"
            Text(
                if (a.enabled) "$lastRan." else "Off. $lastRan.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (a.enabled) MaterialTheme.colorScheme.onSurfaceVariant else pulse.streak,
            )

            detail?.whenText?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SheetLabel("When")
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            detail?.description?.let {
                // Some descriptions are an engineer's notes, paragraphs long: the first few lines
                // say what it does, the rest is there on request.
                var full by remember(a.entityId) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SheetLabel("What it does")
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (full) Int.MAX_VALUE else DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!full && it.length > DESCRIPTION_FOLD_CHARS) {
                        Text(
                            "More",
                            style = MaterialTheme.typography.labelLarge,
                            color = pulse.effort,
                            modifier = Modifier.clickable { full = true }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            if (a.name != a.title) {
                Text(
                    "Named “${a.name}” in Home Assistant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                // Behind-the-scenes automations get no Run: by hand, one parks a camera, pushes to
                // every phone or trips a watchdog.
                if (!a.system) {
                    PulseButton(
                        text = if (running) "Running…" else "Run now",
                        onClick = { confirmRun = true },
                        enabled = !running,
                        tonal = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                a.configId?.let { id ->
                    PulseButton(text = "Edit", onClick = { onEdit(id) }, tonal = true, modifier = Modifier.weight(1f))
                }
            }
            if (a.configId != null) {
                Text(
                    "Edits change Home Assistant's copy. If this automation is also kept in " +
                        "hawksnest-automation, update it there too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Lines of a long description shown before "More". */
private const val DESCRIPTION_LINES = 4

/** Roughly what fits in [DESCRIPTION_LINES] on a phone; longer descriptions offer "More". */
private const val DESCRIPTION_FOLD_CHARS = 180

@Composable
private fun SheetLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

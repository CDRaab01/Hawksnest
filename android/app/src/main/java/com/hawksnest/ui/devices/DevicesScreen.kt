package com.hawksnest.ui.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.CardType
import com.hawksnest.core.logic.DeviceSection
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.rememberHaptics
import com.hawksnest.ui.components.rememberOptimisticOnOff
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The set of device kinds the filter chips can narrow to. `cardTypes == null` ("All") matches
 * everything; the rest map one chip to one or more [CardType]s. Pure view-layer concern.
 */
enum class DeviceFilter(val label: String, val cardTypes: Set<CardType>?) {
    ALL("All", null),
    LIGHTS("Lights", setOf(CardType.LIGHT)),
    LOCKS("Locks", setOf(CardType.LOCK)),
    CLIMATE("Climate", setOf(CardType.CLIMATE)),
    COVERS("Covers", setOf(CardType.COVER)),
    SWITCHES("Switches", setOf(CardType.SWITCH, CardType.FAN)),
    MEDIA("Media", setOf(CardType.MEDIA_PLAYER)),
    SENSORS("Sensors", setOf(CardType.BINARY_SENSOR, CardType.GENERIC, CardType.CAMERA)),
    ALARM("Alarm", setOf(CardType.ALARM)),
    ;

    fun matches(card: CardType): Boolean = cardTypes == null || card in cardTypes
}

/**
 * Devices v2 — a single-column list with a deliberate three-tier rhythm per room:
 * FEATURED devices (locks/climate/alarm) keep their full control cards; everything
 * else collapses into compact rows inside one hairline panel per room (controls
 * with an inline switch first, read-only state rows last). Room headers carry a
 * "N devices - M on" summary and survive filtering. Long-press any row/card to
 * rename or hide it (persisted on-device); hidden devices live behind a quiet
 * footer. Chips are PULSE segments, not stock Material chips.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(
    onOpenEntity: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val query by viewModel.query.collectAsState()
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<DeviceUi?>(null) }
    var hiddenSheet by remember { mutableStateOf(false) }

    // Only offer chips for kinds that are actually present, so the row isn't full of dead filters.
    val present = remember(ui.sections) {
        ui.sections.flatMapTo(HashSet()) { s -> (s.featured + s.controls + s.readonly).map { it.card } }
    }
    val chips = remember(present) {
        DeviceFilter.entries.filter { it == DeviceFilter.ALL || it.cardTypes!!.any(present::contains) }
    }
    val active = if (filter in chips) filter else DeviceFilter.ALL

    // Apply the chip filter per tier; rooms with nothing left disappear entirely.
    val sections = remember(ui.sections, active) {
        if (active == DeviceFilter.ALL) ui.sections
        else ui.sections.mapNotNull { s ->
            val f = s.featured.filter { active.matches(it.card) }
            val c = s.controls.filter { active.matches(it.card) }
            val r = s.readonly.filter { active.matches(it.card) }
            if (f.isEmpty() && c.isEmpty() && r.isEmpty()) null
            else s.copy(featured = f, controls = c, readonly = r, total = f.size + c.size + r.size)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(HawksnestTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("Devices") }
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) viewModel.setQuery("")
                }) {
                    Icon(
                        if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search devices",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (searchOpen) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    placeholder = { Text("Search devices") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "chips") {
            PulseChipRow(chips = chips, selected = active, onSelect = { filter = it })
        }

        sections.forEach { section ->
            item(key = "area:" + section.area) {
                RoomHeader(section)
            }
            section.featured.forEach { device ->
                item(key = device.entityId) {
                    Box(
                        Modifier.combinedClickable(
                            onClick = { onOpenEntity(device.entityId) },
                            onLongClick = { sheetFor = device },
                        ),
                    ) {
                        DeviceControlCard(
                            device,
                            onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                            onOpen = { onOpenEntity(device.entityId) },
                            pending = device.entityId in pending,
                        )
                    }
                }
            }
            val rows = section.controls + section.readonly
            if (rows.isNotEmpty()) {
                item(key = "rows:" + section.area) {
                    PanelCard {
                        rows.forEachIndexed { i, device ->
                            if (i > 0) {
                                HorizontalDivider(color = HawksnestTheme.pulse.hairline, thickness = 1.dp)
                            }
                            DeviceRow(
                                device = device,
                                pending = device.entityId in pending,
                                onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                                onOpen = { onOpenEntity(device.entityId) },
                                onLongPress = { sheetFor = device },
                            )
                        }
                    }
                }
            }
        }

        if (ui.hidden.isNotEmpty() && active == DeviceFilter.ALL && query.isBlank()) {
            item(key = "hidden-footer") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { hiddenSheet = true }
                        .padding(HawksnestTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(HawksnestTheme.spacing.sm))
                    Text(
                        "Hidden devices (" + ui.hidden.size + ")",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    sheetFor?.let { device ->
        DeviceActionsSheet(
            device = device,
            onRename = { viewModel.rename(device.entityId, it) },
            onHide = { viewModel.hide(device.entityId) },
            onDismiss = { sheetFor = null },
        )
    }
    if (hiddenSheet) {
        HiddenDevicesSheet(
            hidden = ui.hidden,
            onUnhide = viewModel::unhide,
            onDismiss = { hiddenSheet = false },
        )
    }
}

/** Room header: area name left, "N devices - M on" summary right. */
@Composable
private fun RoomHeader(section: DeviceSection<DeviceUi>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            section.area.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val summary = buildString {
            append(section.total)
            append(if (section.total == 1) " device" else " devices")
            if (section.activeCount > 0) {
                append(" · ")
                append(section.activeCount)
                append(" on")
            }
        }
        Text(
            summary,
            style = MaterialTheme.typography.labelSmall,
            color = HawksnestTheme.pulse.effort,
        )
    }
}

/** PULSE-styled filter segments (effort-dim fill when selected) — not stock M3 chips. */
@Composable
private fun PulseChipRow(
    chips: List<DeviceFilter>,
    selected: DeviceFilter,
    onSelect: (DeviceFilter) -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        chips.forEach { chip ->
            val active = chip == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (active) pulse.effortDim else pulse.panelHigh)
                    .clickable { onSelect(chip) }
                    .padding(horizontal = HawksnestTheme.spacing.lg, vertical = HawksnestTheme.spacing.sm),
            ) {
                Text(
                    chip.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Icon for a compact device row, by kind. */
private fun rowIcon(card: CardType): ImageVector = when (card) {
    CardType.LIGHT -> Icons.Filled.Lightbulb
    CardType.SWITCH -> Icons.Filled.Power
    CardType.FAN -> Icons.Filled.Air
    CardType.COVER -> Icons.Filled.Blinds
    CardType.MEDIA_PLAYER -> Icons.AutoMirrored.Filled.VolumeUp
    CardType.CAMERA -> Icons.Filled.Videocam
    else -> Icons.Filled.Sensors
}

private val TOGGLE_CARDS = setOf(CardType.LIGHT, CardType.SWITCH, CardType.FAN)

/**
 * One compact device row: a state-tinted icon disc, single-line name, state
 * caption, and — for toggleable kinds — the shared optimistic switch inline.
 * Tap opens the entity detail; long-press opens rename/hide.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: DeviceUi,
    pending: Boolean,
    onCall: (String, Map<String, Any?>) -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val toggleable = device.card in TOGGLE_CARDS
    val (shown, setTarget) = rememberOptimisticOnOff(device.rawState == "on", pending)
    val lit = toggleable && shown

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.sm)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (lit) pulse.strengthDim else pulse.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                rowIcon(device.card),
                contentDescription = null,
                tint = if (lit) pulse.strength else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(HawksnestTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (toggleable) (if (shown) "On" else "Off") else device.stateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (toggleable) {
            Switch(
                checked = shown,
                onCheckedChange = {
                    if (it) haptics.toggleOn() else haptics.toggleOff()
                    setTarget(it)
                    onCall(if (it) "turn_on" else "turn_off", emptyMap())
                },
                colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Long-press sheet: rename (persisted on-device) or hide, with the raw entity id for reference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceActionsSheet(
    device: DeviceUi,
    onRename: (String?) -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(device.entityId) { mutableStateOf(device.name) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HawksnestTheme.pulse.panelHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HawksnestTheme.spacing.lg)
                .padding(bottom = HawksnestTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                device.entityId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                TextButton(onClick = {
                    onRename(name.takeIf { it.isNotBlank() && it != device.name })
                    onDismiss()
                }) { Text("Save") }
                TextButton(onClick = {
                    onHide()
                    onDismiss()
                }) { Text("Hide from list") }
            }
        }
    }
}

/** The hidden-devices shelf: everything the user hid, one tap to restore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenDevicesSheet(
    hidden: List<DeviceUi>,
    onUnhide: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HawksnestTheme.pulse.panelHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HawksnestTheme.spacing.lg)
                .padding(bottom = HawksnestTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            Text(
                "Hidden devices",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            hidden.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            device.entityId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onUnhide(device.entityId) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(HawksnestTheme.spacing.xs))
                        Text("Show")
                    }
                }
            }
        }
    }
}

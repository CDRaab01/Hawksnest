package com.hawksnest.ui.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
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
import com.hawksnest.core.logic.DeviceTier
import com.hawksnest.core.logic.ReadonlyItem
import com.hawksnest.core.logic.entities
import com.hawksnest.core.logic.tierOf
import com.hawksnest.ui.components.ControlTile
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.DeviceGroupRow
import com.hawksnest.ui.components.DeviceGroupSheet
import com.hawksnest.ui.components.DeviceUi
import com.hawksnest.ui.components.PanelCard
import com.hawksnest.ui.components.SectionHeader
import com.hawksnest.ui.components.rememberHaptics
import com.hawksnest.ui.components.rememberOptimisticOnOff
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Devices v3 — the control deck. The tab regrouped by FUNCTION and ordered by IMPORTANCE
 * (core/logic/ControlDeck.kt): needs-attention strip, pinned rail, Security (full lock/alarm
 * cards), Lights & switches (tile grid), Climate & fans, Covers, Media, then Cameras and
 * Sensors demoted to one summary row each that opens a sheet. The Rooms tab browses by
 * place; this tab answers "what can I do". The old chip filter is gone — the sections ARE
 * the categories. Search still bypasses everything: flat results, one tap to detail.
 * Long-press any row/card/tile → rename / pin / hide (a group hides all members at once);
 * hidden devices live behind the quiet footer.
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
    val areas by viewModel.areas.collectAsState()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<DeviceUi?>(null) }
    var groupSheet by remember { mutableStateOf<ReadonlyItem.Group<DeviceUi>?>(null) }
    var groupActions by remember { mutableStateOf<ReadonlyItem.Group<DeviceUi>?>(null) }
    var camerasSheet by remember { mutableStateOf(false) }
    var sensorsSheet by remember { mutableStateOf(false) }
    var hiddenSheet by remember { mutableStateOf(false) }

    val deck = ui.deck

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

        // A non-blank query replaces the whole deck with flat results — one tap to detail.
        if (query.isNotBlank()) {
            if (deck.searchResults.isNotEmpty()) {
                item(key = "search-results") {
                    PanelCard {
                        deck.searchResults.forEachIndexed { i, device ->
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
            } else {
                // A query that matches nothing used to render NOTHING: the deck's other sections
                // are empty for any non-blank query, so the screen was a bare search box over
                // blank space with no indication whether the search had run or the house was
                // gone. Same copy as the web's Devices screen.
                item(key = "search-empty") {
                    PanelCard {
                        Text(
                            "No devices match “$query”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            // ── Needs attention — offline / low battery; absent when the house is healthy ──
            if (deck.attention.isNotEmpty()) {
                item(key = "attention-header") {
                    DeckHeader("Needs attention", deck.attention.size.toString(), warn = true)
                }
                item(key = "attention") {
                    PanelCard {
                        deck.attention.forEachIndexed { i, device ->
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

            // ── Pinned — the user's shortcuts; devices stay in their sections too ──
            if (ui.pinned.isNotEmpty()) {
                item(key = "pinned-header") { DeckHeader("Pinned", "") }
                ui.pinned.filter { tierOf(it.card) == DeviceTier.FEATURED }.forEach { device ->
                    item(key = "pin:" + device.entityId) {
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
                val pinnedRows = ui.pinned.filter { tierOf(it.card) != DeviceTier.FEATURED }
                if (pinnedRows.isNotEmpty()) {
                    item(key = "pinned-rows") {
                        PanelCard {
                            pinnedRows.forEachIndexed { i, device ->
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

            // ── Security — locks then alarm, always full cards ──
            if (deck.security.isNotEmpty()) {
                item(key = "security-header") { DeckHeader("Security", deck.security.size.toString()) }
                deck.security.forEach { device ->
                    item(key = "sec:" + device.entityId) {
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
            }

            // ── Lights & switches — the tile grid, two per row ──
            if (deck.lights.isNotEmpty()) {
                val litCount = deck.lights.count { it.rawState == "on" }
                item(key = "lights-header") {
                    DeckHeader("Lights & switches", if (litCount > 0) litCount.toString() + " on" else "")
                }
                deck.lights.chunked(2).forEachIndexed { rowIdx, pair ->
                    item(key = "lights-row:" + rowIdx) {
                        Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                            pair.forEach { device ->
                                Box(Modifier.weight(1f)) {
                                    ControlTile(
                                        device = device,
                                        caption = areas[device.entityId] ?: device.stateText,
                                        pending = device.entityId in pending,
                                        onToggle = {
                                            viewModel.call(device.entityId, if (it) "turn_on" else "turn_off")
                                        },
                                        onOpen = { onOpenEntity(device.entityId) },
                                        onLongPress = { sheetFor = device },
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── Climate & fans — thermostats as cards, fans as rows ──
            if (deck.climate.isNotEmpty()) {
                item(key = "climate-header") { DeckHeader("Climate", "") }
                deck.climate.filter { it.card == CardType.CLIMATE }.forEach { device ->
                    item(key = "climate:" + device.entityId) {
                        DeviceControlCard(
                            device,
                            onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                            onOpen = { onOpenEntity(device.entityId) },
                            pending = device.entityId in pending,
                        )
                    }
                }
                val fans = deck.climate.filter { it.card == CardType.FAN }
                if (fans.isNotEmpty()) {
                    item(key = "fans") {
                        PanelCard {
                            fans.forEachIndexed { i, device ->
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

            // ── Covers + Media — compact rows; sections vanish when empty ──
            listOf("Covers" to deck.covers, "Media" to deck.media).forEach { (label, devices) ->
                if (devices.isNotEmpty()) {
                    item(key = label + "-header") { DeckHeader(label, "") }
                    item(key = label + "-rows") {
                        PanelCard {
                            devices.forEachIndexed { i, device ->
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

            // ── Cameras + Sensors — one summary row each; the detail lives in a sheet ──
            val sensorEntityCount = deck.sensorSections.sumOf { s -> s.readonlyItems.sumOf { it.entities().size } }
            val sensorActiveCount = deck.sensorSections.sumOf { it.activeCount }
            if (deck.cameraGroups.isNotEmpty() || sensorEntityCount > 0) {
                item(key = "monitor-header") { DeckHeader("Cameras & sensors", "") }
                item(key = "monitor-rows") {
                    PanelCard {
                        if (deck.cameraGroups.isNotEmpty()) {
                            SummaryRow(
                                icon = Icons.Filled.Videocam,
                                title = deck.cameraGroups.size.toString() +
                                    if (deck.cameraGroups.size == 1) " camera" else " cameras",
                                caption = deck.cameraGroups.joinToString(" · ") { it.name },
                                onClick = { camerasSheet = true },
                            )
                        }
                        if (deck.cameraGroups.isNotEmpty() && sensorEntityCount > 0) {
                            HorizontalDivider(color = HawksnestTheme.pulse.hairline, thickness = 1.dp)
                        }
                        if (sensorEntityCount > 0) {
                            SummaryRow(
                                icon = Icons.Filled.Sensors,
                                title = sensorEntityCount.toString() + " sensors",
                                caption = if (sensorActiveCount > 0) sensorActiveCount.toString() + " active" else "all quiet",
                                onClick = { sensorsSheet = true },
                            )
                        }
                    }
                }
            }

            if (ui.hidden.isNotEmpty()) {
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
    }

    sheetFor?.let { device ->
        DeviceActionsSheet(
            device = device,
            pinned = ui.pinned.any { it.entityId == device.entityId },
            onRename = { viewModel.rename(device.entityId, it) },
            onHide = { viewModel.hide(device.entityId) },
            onTogglePin = { viewModel.togglePin(device.entityId) },
            onMovePin = { viewModel.movePin(device.entityId, it) },
            onDismiss = { sheetFor = null },
        )
    }
    groupSheet?.let { group ->
        DeviceGroupSheet(
            group = group,
            onOpenEntity = {
                groupSheet = null
                onOpenEntity(it)
            },
            onDismiss = { groupSheet = null },
        )
    }
    groupActions?.let { group ->
        DeviceGroupActionsSheet(
            group = group,
            onHideAll = { viewModel.hideAll(group.members.map { it.entityId }) },
            onDismiss = { groupActions = null },
        )
    }
    if (camerasSheet) {
        GroupListSheet(
            title = "Cameras",
            groups = deck.cameraGroups,
            onOpenGroup = {
                camerasSheet = false
                groupSheet = it
            },
            onLongPressGroup = {
                camerasSheet = false
                groupActions = it
            },
            onDismiss = { camerasSheet = false },
        )
    }
    if (sensorsSheet) {
        SensorsSheet(
            sections = deck.sensorSections,
            onOpenEntity = {
                sensorsSheet = false
                onOpenEntity(it)
            },
            onOpenGroup = {
                sensorsSheet = false
                groupSheet = it
            },
            onDismiss = { sensorsSheet = false },
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

/** Deck section header: uppercase label left, quiet summary right. */
@Composable
private fun DeckHeader(label: String, summary: String, warn: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (warn) HawksnestTheme.pulse.streak else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (summary.isNotEmpty()) {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) HawksnestTheme.pulse.streak else HawksnestTheme.pulse.effort,
            )
        }
    }
}

/** One summary row (cameras / sensors): icon disc, count, caption, chevron → sheet. */
@Composable
private fun SummaryRow(
    icon: ImageVector,
    title: String,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HawksnestTheme.spacing.md, vertical = HawksnestTheme.spacing.sm)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(HawksnestTheme.pulse.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(HawksnestTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A sheet of device groups (the Cameras summary's detail). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupListSheet(
    title: String,
    groups: List<ReadonlyItem.Group<DeviceUi>>,
    onOpenGroup: (ReadonlyItem.Group<DeviceUi>) -> Unit,
    onLongPressGroup: (ReadonlyItem.Group<DeviceUi>) -> Unit,
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            groups.forEach { group ->
                DeviceGroupRow(
                    group = group,
                    onOpen = { onOpenGroup(group) },
                    onLongPress = { onLongPressGroup(group) },
                )
            }
        }
    }
}

/** The Sensors summary's detail: every remaining read-only item, grouped per room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorsSheet(
    sections: List<DeviceSection<DeviceUi>>,
    onOpenEntity: (String) -> Unit,
    onOpenGroup: (ReadonlyItem.Group<DeviceUi>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HawksnestTheme.pulse.panelHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HawksnestTheme.spacing.lg)
                .padding(bottom = HawksnestTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            Text("Sensors", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            sections.forEach { section ->
                Text(
                    section.area.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
                )
                section.readonlyItems.forEach { item ->
                    when (item) {
                        is ReadonlyItem.Single -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onOpenEntity(item.device.entityId) }
                                .padding(vertical = HawksnestTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.device.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.device.stateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is ReadonlyItem.Group -> DeviceGroupRow(
                            group = item,
                            onOpen = { onOpenGroup(item) },
                        )
                    }
                }
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


/** Long-press sheet for a device group: hide every member at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceGroupActionsSheet(
    group: ReadonlyItem.Group<DeviceUi>,
    onHideAll: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                group.members.size.toString() + " entities on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                onHideAll()
                onDismiss()
            }) { Text("Hide device from list") }
        }
    }
}

/**
 * Long-press sheet: rename (persisted on-device), pin/unpin (+ reorder while pinned),
 * or hide, with the raw entity id for reference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceActionsSheet(
    device: DeviceUi,
    pinned: Boolean,
    onRename: (String?) -> Unit,
    onHide: () -> Unit,
    onTogglePin: () -> Unit,
    onMovePin: (Int) -> Unit,
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
                    onTogglePin()
                    onDismiss()
                }) { Text(if (pinned) "Unpin" else "Pin to top") }
                TextButton(onClick = {
                    onHide()
                    onDismiss()
                }) { Text("Hide from list") }
            }
            if (pinned) {
                Row(horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm)) {
                    TextButton(onClick = { onMovePin(-1) }) { Text("Move up") }
                    TextButton(onClick = { onMovePin(1) }) { Text("Move down") }
                }
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

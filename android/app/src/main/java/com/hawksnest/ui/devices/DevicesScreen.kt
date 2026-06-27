package com.hawksnest.ui.devices

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.CardType
import com.hawksnest.ui.components.DeviceControlCard
import com.hawksnest.ui.components.SectionHeader
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
    SENSORS("Sensors", setOf(CardType.BINARY_SENSOR, CardType.GENERIC)),
    ALARM("Alarm", setOf(CardType.ALARM)),
    ;

    fun matches(card: CardType): Boolean = cardTypes == null || card in cardTypes
}

/**
 * Devices — every controllable entity, in a dense 2-column staggered grid. Filter chips at the top
 * narrow the list to one kind (lights, locks, climate…). With "All" selected the grid keeps room
 * grouping (a full-width area header before each room's cards); with a type filter active it
 * collapses to a flat grid of just that kind. The grid is the screen's only scroll container, so it
 * can scroll (a Lazy grid nested in a verticalScroll Column would crash).
 */
@Composable
fun DevicesScreen(
    onOpenEntity: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsState()
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }

    // Only offer chips for kinds that are actually present, so the row isn't full of dead filters.
    val present = remember(groups) { groups.flatMapTo(HashSet()) { g -> g.devices.map { it.card } } }
    val chips = remember(present) {
        DeviceFilter.entries.filter { it == DeviceFilter.ALL || it.cardTypes!!.any(present::contains) }
    }
    // A selected filter whose devices have all gone away falls back to All.
    val active = if (filter in chips) filter else DeviceFilter.ALL

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(HawksnestTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        verticalItemSpacing = HawksnestTheme.spacing.md,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            SectionHeader("Devices")
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            FilterChipRow(chips = chips, selected = active, onSelect = { filter = it })
        }

        if (active == DeviceFilter.ALL) {
            groups.forEach { group ->
                item(span = StaggeredGridItemSpan.FullLine, key = "area:${group.area}") {
                    Text(
                        group.area.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = HawksnestTheme.spacing.sm),
                    )
                }
                items(group.devices, key = { it.entityId }) { device ->
                    DeviceControlCard(
                        device,
                        onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                        onOpen = { onOpenEntity(device.entityId) },
                    )
                }
            }
        } else {
            val flat = groups.flatMap { it.devices }.filter { active.matches(it.card) }
            items(flat, key = { it.entityId }) { device ->
                DeviceControlCard(
                    device,
                    onCall = { service, extra -> viewModel.call(device.entityId, service, extra) },
                    onOpen = { onOpenEntity(device.entityId) },
                )
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    chips: List<DeviceFilter>,
    selected: DeviceFilter,
    onSelect: (DeviceFilter) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
    ) {
        chips.forEach { chip ->
            FilterChip(
                selected = chip == selected,
                onClick = { onSelect(chip) },
                label = { Text(chip.label) },
            )
        }
    }
}

package com.hawksnest.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Spotter-style bottom navigation: a flat panel with a hairline top rule and the active item in
 * effort cyan. **Home is a large raised circle** in the center (filled when active). Items:
 * Devices · Rooms · HOME · History · Settings.
 *
 * The panel is a fixed, compact [BarHeight]; the Home circle is bigger than the panel and simply
 * **protrudes above it** (placed at the top and lifted with an offset, so its layout footprint
 * never inflates the bar). Every label sits on a common baseline along the bottom.
 */
@Composable
fun PulseBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    Column(modifier) {
        HorizontalDivider(thickness = 1.dp, color = pulse.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(pulse.panel)
                // Lift the tab row above the system gesture/3-button bar; the panel background
                // still fills behind the inset so it reads as one continuous bar.
                .navigationBarsPadding()
                .height(BarHeight),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
        ) {
            TopLevelDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route
                if (dest.center) {
                    CenterItem(dest, selected) { onNavigate(dest) }
                } else {
                    FlatItem(dest, selected) { onNavigate(dest) }
                }
            }
        }
    }
}

/** Compact panel height. The Home circle exceeds this and protrudes above it. */
private val BarHeight = 60.dp

@Composable
private fun RowScope.FlatItem(dest: TopLevelDestination, selected: Boolean, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    val tint = if (selected) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(bottom = HawksnestTheme.spacing.sm),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (selected) dest.icon else dest.iconOutlined,
            contentDescription = dest.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(dest.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun RowScope.CenterItem(dest: TopLevelDestination, selected: Boolean, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // requiredSize lets the 60dp circle stay full-size and overflow the compact bar; pinned to
        // the top and lifted so it pokes above the panel without enlarging the bar's height.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-22).dp)
                .requiredSize(60.dp)
                .clip(CircleShape)
                .background(if (selected) pulse.effort else pulse.panelHigh)
                .then(if (selected) Modifier else Modifier.border(1.dp, pulse.hairline, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = dest.label,
                tint = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            dest.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) pulse.effort else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HawksnestTheme.spacing.sm),
        )
    }
}

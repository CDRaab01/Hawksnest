package com.hawksnest.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/** The app's bottom navigation: a flat panel with a hairline top rule, selection in effort cyan. */
@Composable
fun PulseBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    Column(modifier) {
        HorizontalDivider(thickness = 1.dp, color = pulse.hairline)
        NavigationBar(
            containerColor = pulse.panel,
            tonalElevation = 0.dp,
        ) {
            TopLevelDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(dest) },
                    icon = {
                        Icon(
                            imageVector = if (selected) dest.icon else dest.iconOutlined,
                            contentDescription = dest.label,
                        )
                    },
                    label = { Text(dest.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = pulse.effort,
                        selectedTextColor = pulse.effort,
                        indicatorColor = pulse.effortDim,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

package com.hawksnest.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * An instrument readout: an uppercase caption, a mono value (optionally rolling in via
 * [animatedValue]), and an optional [sparkline] giving the number its recent history. [accent]
 * is the channel that owns this stat — it tints the value and the panel stroke.
 */
@Composable
fun StatTile(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    animatedValue: Int? = null,
    valueSuffix: String = "",
    accent: Color? = null,
    icon: (@Composable () -> Unit)? = null,
    sparkline: List<Float>? = null,
    onClick: (() -> Unit)? = null,
) {
    PanelCard(
        modifier = modifier,
        onClick = onClick,
        channel = accent,
        contentPadding = 14.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
            ) {
                icon?.invoke()
                val valueColor = accent ?: MaterialTheme.colorScheme.onSurface
                if (animatedValue != null) {
                    TickerNumber(
                        target = animatedValue,
                        suffix = valueSuffix,
                        style = HawksnestTheme.dataType.dataSmall,
                        color = valueColor,
                    )
                } else {
                    DataText(
                        text = value.orEmpty(),
                        style = HawksnestTheme.dataType.dataSmall,
                        color = valueColor,
                    )
                }
            }
            if (sparkline != null && sparkline.size >= 2) {
                Sparkline(
                    points = sparkline,
                    channel = accent ?: HawksnestTheme.pulse.effort,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                )
            }
        }
    }
}

package com.hawksnest.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.PulseMotion
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * A labeled horizontal channel bar against the raised-panel track — per-muscle volume on the
 * workout summary, or any "share of max" comparison. [value]/[maxValue] sets the fill; the
 * optional [valueText] renders as a mono numeral at the trailing edge.
 */
@Composable
fun HeatBar(
    label: String,
    value: Float,
    maxValue: Float,
    modifier: Modifier = Modifier,
    channel: Color = HawksnestTheme.pulse.effort,
    valueText: String? = null,
) {
    val fraction by animateFloatAsState(
        targetValue = if (maxValue > 0f) (value / maxValue).coerceIn(0f, 1f) else 0f,
        animationSpec = PulseMotion.data(),
        label = "heatBar",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (valueText != null) {
                DataText(
                    text = valueText,
                    style = HawksnestTheme.dataType.numeral,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(HawksnestTheme.pulse.panelHigh, CircleShape),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(channel, CircleShape),
                )
            }
        }
    }
}

package com.hawksnest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.ALARM_TRANSITIONAL
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.alarmView
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion
import com.hawksnest.ui.theme.color
import com.hawksnest.ui.theme.dim

/**
 * The Off / Home / Away control as one hairline pill: the active segment wears the alarm state's
 * own channel (recovery when disarmed, effort when armed, streak when triggered) with an animated
 * fill, and the tapped segment holds a spinner while HA works through arming/exit delay.
 *
 * **Non-optimistic**, like every security surface here: the highlight follows `rawState` only —
 * a tap shows a spinner, never a selected state HA hasn't confirmed.
 */
@Composable
fun ArmSegments(
    rawState: String,
    pending: Boolean,
    onArm: (service: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val view = alarmView(rawState)
    val activeColor = pulse.color(view.channel)
    val activeDim = pulse.dim(view.channel)

    // Which segment was tapped, so its spinner (not all three) shows while HA settles.
    var tapped by remember { mutableStateOf<String?>(null) }
    val transitional = rawState in ALARM_TRANSITIONAL
    LaunchedEffect(pending, transitional) { if (!pending && !transitional) tapped = null }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.md)
            .clip(CircleShape)
            .border(1.dp, pulse.hairline, CircleShape)
            .background(pulse.panelHigh)
            .padding(4.dp),
    ) {
        ARM_BUTTONS.forEach { b ->
            Segment(
                label = b.label,
                active = rawState == b.state,
                activeColor = activeColor,
                activeDim = activeDim,
                busy = (pending || transitional) && tapped == b.service,
                enabled = !pending,
                onClick = {
                    haptics.toggleOn()
                    tapped = b.service
                    onArm(b.service)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowScope.Segment(
    label: String,
    active: Boolean,
    activeColor: Color,
    activeDim: Color,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill by animateColorAsState(
        targetValue = if (active) activeDim else Color.Transparent,
        animationSpec = PulseMotion.standard(),
        label = "segmentFill",
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick, enabled = enabled && !busy),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = activeColor,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

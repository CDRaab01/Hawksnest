package com.hawksnest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.SIREN_CONFIRM_WINDOW_MS
import com.hawksnest.core.logic.SirenTap
import com.hawksnest.core.logic.sirenTap
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.delay

/**
 * The siren control everywhere Devices draws one (rows, search results, full cards): two taps to
 * sound, one to silence ([sirenTap]). The label says what the NEXT tap does, so the armed state
 * reads as a question ("Tap again to sound") rather than a hidden timer.
 *
 * Not optimistic: while HA works on the call the pill holds a spinner, and "sounding" is only ever
 * shown from HA's own state.
 */
@Composable
fun SirenToggle(
    on: Boolean,
    pending: Boolean,
    enabled: Boolean,
    onSet: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    var armed by remember { mutableStateOf(false) }
    // An unconfirmed first tap lapses on its own.
    LaunchedEffect(armed) {
        if (armed) {
            delay(SIREN_CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    // The siren changing underneath us (sounded or silenced elsewhere) cancels a half-made confirm.
    LaunchedEffect(on) { armed = false }

    val hot = on || armed
    val label = when {
        on -> "Silence"
        armed -> "Tap again to sound"
        else -> "Sound"
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (hot) pulse.streakDim else pulse.panelHigh)
            .border(1.dp, if (hot) pulse.streak else pulse.hairline, CircleShape)
            .clickable(enabled = enabled && !pending, role = Role.Button, onClickLabel = label) {
                when (sirenTap(on, armed)) {
                    SirenTap.ARM -> {
                        haptics.toggleOn()
                        armed = true
                    }
                    SirenTap.SOUND -> {
                        haptics.toggleOn()
                        armed = false
                        onSet(true)
                    }
                    SirenTap.SILENCE -> {
                        haptics.toggleOff()
                        armed = false
                        onSet(false)
                    }
                }
            }
            .semantics { stateDescription = if (on) "Sounding" else "Quiet" }
            .padding(horizontal = HawksnestTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = pulse.streak,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (hot) pulse.streak else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

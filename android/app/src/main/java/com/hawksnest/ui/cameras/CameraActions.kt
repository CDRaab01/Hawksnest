package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The things the CAMERA does, as opposed to what you see — one row, directly under the picture.
 *
 * Split from the view controls (which now overlay the frame, see [CameraOverlay]) because the old
 * single row mixed four unrelated kinds of thing: navigation, view options, actions, and status.
 * Wrapping them together is what made it two rows deep.
 *
 * **Equal-weight columns, never a wrapping flow.** The set genuinely varies — Talk and Reply only
 * where there is a speaker, Siren only where one exists — so the row is built from whatever
 * applies and each item takes an equal share. That cannot wrap, cannot clip, and cannot render a
 * label one character per line the way the old FlowRow did when squeezed.
 *
 * An action a camera cannot perform is ABSENT, not disabled. A greyed button that never works
 * teaches people to distrust the whole row; this follows the rule Move already used for non-PTZ
 * cameras.
 */
@Composable
fun CameraActions(
    /** Each slot is absent (null) when the camera cannot do that thing — never disabled. */
    talk: (@Composable RowScope.() -> Unit)? = null,
    reply: (@Composable RowScope.() -> Unit)? = null,
    snapshot: (@Composable RowScope.() -> Unit)? = null,
    siren: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val slots = listOfNotNull(talk, reply, snapshot, siren)
    if (slots.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().testTag("cameraActions"),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        slots.forEach { it() }
    }
}

/**
 * One action: stacked icon over label, filling its share of the row.
 *
 * Stacked rather than side-by-side because a horizontal icon+label needs roughly twice the width,
 * which is what forced the old row to wrap. Vertically there is room to spare.
 */
@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    active: Boolean = false,
    tint: Color? = null,
) {
    val pulse = HawksnestTheme.pulse
    val bg = when {
        active -> pulse.recoveryDim
        accent -> pulse.effortDim
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = tint ?: when {
        active -> pulse.recovery
        accent -> pulse.effort
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

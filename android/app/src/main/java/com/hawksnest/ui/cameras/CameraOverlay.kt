package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * Chrome shown ONLY in fullscreen, where the control row above the picture is off-screen.
 *
 * The portrait layout deliberately keeps every control in that row on a black background rather
 * than on the picture. An earlier revision moved them onto the frame and it was worse in three
 * ways at once: 30dp targets too small to hit, controls competing with a bright daylight image,
 * and a camera name drawn over the one the camera already burns in. That is not repeated here.
 *
 * Fullscreen is the exception, because there is nowhere else for them to go. It used to show a
 * single exit button and nothing else, which meant losing Mute, Talk, Reply and Siren exactly
 * when the picture was biggest. This is the minimum set that makes fullscreen usable rather than
 * merely large.
 *
 * Must be a sibling of the zoomed content inside [ZoomableFrame]'s Box, NOT inside the
 * `graphicsLayer` — otherwise the buttons scale and slide with the picture when it is magnified.
 */
@Composable
fun BoxScope.FullscreenChrome(
    muted: Boolean,
    onToggleMute: () -> Unit,
    onExitFullscreen: () -> Unit,
    /** Null when this camera has no speaker — absent, not disabled, matching the portrait row. */
    onReply: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .testTag("fullscreenChrome"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onReply != null) {
            GlassButton(Icons.Filled.Campaign, "Quick reply", false, onReply)
        }
        GlassButton(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            if (muted) "Unmute" else "Mute",
            !muted,
            onToggleMute,
        )
        GlassButton(Icons.Filled.FullscreenExit, "Exit fullscreen", true, onExitFullscreen)
    }
}

/**
 * 40dp, not the 30dp the reverted revision used. Fullscreen is a one-handed, arm's-length context
 * and these are the only targets on screen, so they can afford to be a proper size.
 */
@Composable
private fun GlassButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val pulse = HawksnestTheme.pulse
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SCRIM)
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) pulse.recovery else Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Dark enough to stay legible over a blown-out daylight frame. Camera footage is the worst case
 * for overlay contrast — a white label on a white garage door.
 */
private val SCRIM = Color(0xB30B0D10)

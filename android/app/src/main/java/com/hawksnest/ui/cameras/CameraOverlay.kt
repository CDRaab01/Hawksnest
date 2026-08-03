package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.unit.dp
import com.hawksnest.ui.theme.HawksnestTheme

/**
 * The controls that live ON the picture: status, view options, and where you are in the camera
 * list. Everything that changes what you SEE, as opposed to what the camera DOES.
 *
 * Three things drove moving these off the row above the frame:
 *
 * 1. **They wrapped.** Eight chips above a 16:9 frame took two rows on a phone, which cost roughly
 *    a tenth of the screen and pushed the transport toward the fold. The old comment in
 *    `CameraPlayer` already conceded this.
 * 2. **Status about the picture belongs on the picture.** The Live/Recorded dot could end up on
 *    the second row, detached from the thing it describes.
 * 3. **Fullscreen used to hide all of it.** `if (!fullscreen)` dropped mute, quality and the way
 *    back out — you lost every control exactly when the picture was biggest.
 *
 * **Pinned, not auto-hiding.** Every video app fades its chrome; this one shouldn't. A security
 * camera gets glanced at, and never having to tap the picture to find out whether you are muted
 * is worth more than an unobstructed frame. Decided with the owner 2026-08-03.
 *
 * Must be placed as a sibling of the zoomed content inside [ZoomableFrame]'s Box, NOT inside the
 * `graphicsLayer` — otherwise the controls scale and slide with the picture when it is magnified.
 */
@Composable
fun CameraOverlay(
    isLive: Boolean,
    cameraName: String,
    muted: Boolean,
    onToggleMute: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    /** Null hides the control entirely — the camera has no sub stream / no PTZ. */
    qualityLow: Boolean?,
    onQualityChange: (Boolean) -> Unit,
    ptzActive: Boolean?,
    onTogglePtz: () -> Unit,
    /** Position in the camera list, for the swipe dots. Null hides them (one camera). */
    index: Int?,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("cameraOverlay")) {
        StatusBadge(
            isLive = isLive,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
        // The name rides the picture too, so the frame identifies itself while you swipe —
        // previously it was only in the switcher dropdown that swiping replaces.
        Text(
            cameraName,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            if (ptzActive != null) {
                GlassButton(Icons.Filled.OpenWith, "Move", ptzActive, onTogglePtz)
            }
            if (qualityLow != null) {
                GlassToggleText(
                    label = if (qualityLow) "SD" else "HD",
                    active = !qualityLow,
                    onClick = { onQualityChange(!qualityLow) },
                )
            }
            GlassButton(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                if (muted) "Unmute" else "Mute",
                !muted,
                onToggleMute,
            )
            GlassButton(
                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                if (fullscreen) "Exit fullscreen" else "Fullscreen",
                fullscreen,
                onToggleFullscreen,
            )
        }
        if (index != null && count > 1) {
            SwipeDots(
                index = index,
                count = count,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
    }
}

/** LIVE / RECORDED, on the frame. Green only when it is genuinely live. */
@Composable
private fun StatusBadge(isLive: Boolean, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    val fg = if (isLive) pulse.recovery else Color.White
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(SCRIM)
            .border(1.dp, fg.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(fg))
        Text(
            if (isLive) "LIVE" else "RECORDED",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Where you are in the camera list.
 *
 * The swipe has no other affordance — without these, a gesture that changes what you are looking
 * at is invisible until you happen to try it, and there is no way to tell how many cameras you
 * are swiping through.
 */
@Composable
private fun SwipeDots(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag("swipeDots"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val on = i == index
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (on) 11.dp else 4.dp)
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.34f)),
            )
        }
    }
}

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
            .size(30.dp)
            .clip(CircleShape)
            .background(SCRIM)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) pulse.recovery else Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The quality toggle, as one button that names the CURRENT state rather than two chips. */
@Composable
private fun GlassToggleText(label: String, active: Boolean, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    Box(
        Modifier
            .height(30.dp)
            .width(34.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(SCRIM)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) pulse.recovery else Color.White,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Dark enough to stay legible over a blown-out daylight frame, translucent enough not to hide the
 * picture. Camera footage is the worst case for overlay contrast — a white garage door behind a
 * white label.
 */
private val SCRIM = Color(0xB30B0D10)

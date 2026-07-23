package com.hawksnest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.Channel
import com.hawksnest.core.logic.LockPhase
import com.hawksnest.core.logic.LockVaultView
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion
import com.hawksnest.ui.theme.color
import com.hawksnest.ui.theme.dim
import com.hawksnest.ui.theme.on

/**
 * The vault presentation around the lock's slide-to-act track. [SlideToAct] inside is unchanged —
 * same drag-to-commit mechanics, same honest non-optimistic pending — this component adds the
 * state's voice: a deadbolt glyph that physically throws when HA echoes `locked` (never on the
 * gesture), a recovery-green secure glow while locked, a streak-orange jammed frame with a reject
 * buzz, and a charge shimmer over the track while a command is in flight. The settle "thunk"
 * (confirm haptic when a busy lock lands) lives here too, so the whole feel travels with the
 * widget.
 */
@Composable
fun LockVault(
    view: LockVaultView,
    pending: Boolean,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val busy = pending || view.transitional

    // The settle thunk: a busy lock landing on locked/unlocked is the physical bolt moving.
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(view.phase, pending) {
        val settledNow = view.phase == LockPhase.LOCKED || view.phase == LockPhase.UNLOCKED
        if (wasBusy && settledNow && !pending) haptics.confirm()
        wasBusy = busy
    }
    // One reject buzz on entering the jammed state — a jam is a failure, and should feel like one.
    LaunchedEffect(view.phase) {
        if (view.phase == LockPhase.JAMMED) haptics.reject()
    }

    val frameColor by animateColorAsState(
        targetValue = when (view.stateChannel) {
            Channel.RECOVERY -> pulse.recovery.copy(alpha = 0.35f)
            Channel.STREAK -> pulse.streak.copy(alpha = 0.6f)
            else -> pulse.hairline
        },
        animationSpec = PulseMotion.standard(),
        label = "vaultFrame",
    )
    val glow by animateFloatAsState(
        targetValue = if (view.phase == LockPhase.LOCKED) 1f else 0f,
        animationSpec = PulseMotion.emphasized(),
        label = "vaultGlow",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, frameColor, MaterialTheme.shapes.medium)
            .drawBehind {
                // Secure underglow: recovery tone breathing up from the bolt row when locked.
                if (glow > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to pulse.recovery.copy(alpha = 0.10f * glow),
                            1f to pulse.recovery.copy(alpha = 0f),
                        ),
                    )
                }
            }
            .padding(HawksnestTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.md),
        ) {
            DeadboltGlyph(thrown = view.boltThrown, jammed = view.phase == LockPhase.JAMMED)
            Text(
                view.label,
                style = MaterialTheme.typography.labelLarge,
                color = when (view.stateChannel) {
                    Channel.RECOVERY -> pulse.recovery
                    Channel.STREAK -> pulse.streak
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Box(Modifier.padding(top = HawksnestTheme.spacing.md)) {
            SlideToAct(
                label = view.actionLabel,
                pendingLabel = view.pendingLabel,
                icon = if (view.boltThrown) Icons.Filled.LockOpen else Icons.Filled.Lock,
                channel = pulse.color(view.actionChannel),
                onChannel = pulse.on(view.actionChannel),
                dimChannel = pulse.dim(view.actionChannel),
                pending = busy,
                enabled = view.enabled,
                onCommit = onCommit,
                testTag = testTag,
            )
            if (busy) {
                ChargeShimmer(
                    channel = pulse.color(view.actionChannel),
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * A deadbolt seen face-on: a hairline strike track whose channel-colored bolt slides home when
 * [thrown]. The throw animates on HA's echo (the caller flips [thrown] from the echoed state) —
 * this glyph never moves on the user's gesture.
 */
@Composable
private fun DeadboltGlyph(thrown: Boolean, jammed: Boolean, modifier: Modifier = Modifier) {
    val pulse = HawksnestTheme.pulse
    val throwFraction by animateFloatAsState(
        targetValue = if (thrown) 1f else 0f,
        animationSpec = PulseMotion.emphasized(),
        label = "boltThrow",
    )
    // A jam leaves the bolt visibly stuck partway — neither home nor retracted.
    val fraction = if (jammed) 0.45f else throwFraction
    val boltColor = when {
        jammed -> pulse.streak
        thrown -> pulse.recovery
        else -> pulse.hairlineStrong
    }
    Canvas(modifier.width(44.dp).height(16.dp)) {
        val radius = CornerRadius(size.height / 2f)
        // The track the bolt rides in.
        drawRoundRect(color = pulse.panelHigh, cornerRadius = radius)
        drawRoundRect(color = pulse.hairline, cornerRadius = radius, style = Stroke(1.dp.toPx()))
        // The bolt: retracted it hides at the left; thrown it fills to the strike side.
        val minW = size.height
        val boltWidth = minW + (size.width - minW) * fraction
        drawRoundRect(
            color = boltColor,
            topLeft = Offset(0f, 0f),
            size = Size(boltWidth, size.height),
            cornerRadius = radius,
        )
    }
}

/** A slow light-band sweeping the track while a command is in flight — the charge shimmer. */
@Composable
private fun ChargeShimmer(channel: Color, modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "chargeShimmer")
    val x by sweep.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(PulseMotion.DATA * 2, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chargeShimmerX",
    )
    Box(
        modifier
            .clip(CircleShape)
            .drawBehind {
                val bandWidth = size.width * 0.3f
                val start = size.width * x
                drawRect(
                    Brush.horizontalGradient(
                        0f to channel.copy(alpha = 0f),
                        0.5f to channel.copy(alpha = 0.12f),
                        1f to channel.copy(alpha = 0f),
                        startX = start,
                        endX = start + bandWidth,
                    ),
                    topLeft = Offset(start, 0f),
                    size = Size(bandWidth, size.height),
                )
            },
    )
}

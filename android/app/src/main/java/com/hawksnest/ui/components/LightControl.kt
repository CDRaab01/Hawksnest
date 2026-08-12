package com.hawksnest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.tickCrossed
import com.hawksnest.core.logic.washAlpha
import com.hawksnest.ui.theme.HawksnestTheme
import com.hawksnest.ui.theme.PulseMotion
import kotlin.math.roundToInt

/** Default warmth for lights that report no color info — a comfortable neutral-warm. */
private const val NEUTRAL_WARMTH = 0.35f

/**
 * The light control: a switch for on/off and a full-width brightness slider, the same shape the fan
 * uses. The panel behind it carries a glow wash tinted by the light's own warmth (cool blue-white →
 * warm amber) at the current level, so a room's lights still read at a glance.
 *
 * **This replaced a drag-anywhere vertical "pillar" (2026-08-12).** The pillar mapped the full
 * 0–100% range onto ~148dp of travel with no thumb to grab, which made small corrections nearly
 * impossible — every touch was a commit. A slider spans the card's full width, has a thumb you
 * aim at, and its value can be nudged; the same gesture surface no longer doubles as the toggle.
 * If a future redesign brings back an ambient level surface, keep the discrete toggle and the
 * thumb — the touchiness was the gesture, not the visual.
 *
 * Optimistic like every light control here: the switch follows the finger, the slider follows the
 * drag, HA's echo reconciles, and a failed call snaps back via [rememberOptimisticOnOff]. HA hears
 * exactly one call per gesture, on release (`dimCommit` — the floor commits a real `turn_off`).
 */
@Composable
fun LightControl(
    on: Boolean,
    dimmable: Boolean,
    pct: Int,
    warmth: Float?,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    onCommitPct: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val pulse = HawksnestTheme.pulse
    val haptics = rememberHaptics()
    val (shownOn, setOnTarget) = rememberOptimisticOnOff(on, pending)

    // Live level: local while dragging, resynced to HA's echo when [pct] changes.
    var livePct by remember(pct) { mutableFloatStateOf(pct.toFloat()) }
    val shownPct = if (shownOn) livePct.roundToInt() else 0

    val warm = lerp(pulse.effort, pulse.streak, warmth ?: NEUTRAL_WARMTH)
    val wash by animateColorAsState(
        targetValue = warm.copy(alpha = washAlpha(shownOn, dimmable, shownPct)),
        animationSpec = PulseMotion.standard(),
        label = "lightWash",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = HawksnestTheme.spacing.sm)
            .clip(MaterialTheme.shapes.medium)
            .background(wash)
            .padding(HawksnestTheme.spacing.md)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HawksnestTheme.spacing.sm),
        ) {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = if (shownOn) warm else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (shownOn) "On" else "Off",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (dimmable && shownOn) {
                // Live readout: it tracks the drag, so the number under the finger is the number
                // that will be committed.
                DataText(
                    text = "$shownPct%",
                    style = HawksnestTheme.dataType.dataSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = pulse.effort,
                    strokeWidth = 2.dp,
                )
            }
            Switch(
                checked = shownOn,
                enabled = enabled,
                // M3 draws the switch 52×32dp; the tappable node inherits that, which is under
                // the 48dp a11y floor. sizeIn grows the touch target without moving the artwork.
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onCheckedChange = {
                    if (it) haptics.toggleOn() else haptics.toggleOff()
                    setOnTarget(it)
                    onToggle(it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = pulse.effort),
            )
        }

        if (dimmable) {
            Slider(
                value = livePct,
                onValueChange = { next ->
                    tickCrossed(livePct.roundToInt(), next.roundToInt())?.let { haptics.threshold() }
                    livePct = next
                },
                onValueChangeFinished = {
                    haptics.confirm()
                    val committed = livePct.roundToInt()
                    setOnTarget(committed > 0)
                    onCommitPct(committed)
                },
                // The floor is a real "off" (see `dimCommit`), so the range starts at 0.
                valueRange = 0f..100f,
                enabled = enabled && shownOn,
                colors = SliderDefaults.colors(
                    thumbColor = warm,
                    activeTrackColor = warm,
                    inactiveTrackColor = pulse.panelHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Brightness" },
            )
        }
    }
}

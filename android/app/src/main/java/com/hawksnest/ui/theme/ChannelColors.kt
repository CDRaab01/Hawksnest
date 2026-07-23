package com.hawksnest.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.hawksnest.core.logic.AlarmGlyph
import com.hawksnest.core.logic.Channel

/** Map a pure [Channel] (from core/logic) to its concrete PULSE base color. */
fun PulseColors.color(channel: Channel): Color = when (channel) {
    Channel.EFFORT -> effort
    Channel.STRENGTH -> strength
    Channel.STREAK -> streak
    Channel.RECOVERY -> recovery
}

/** Dim variant of a [Channel]'s color (tints, indicators). */
fun PulseColors.dim(channel: Channel): Color = when (channel) {
    Channel.EFFORT -> effortDim
    Channel.STRENGTH -> strengthDim
    Channel.STREAK -> streakDim
    Channel.RECOVERY -> recoveryDim
}

/** Content color for text/icons atop a [Channel]'s base fill. */
fun PulseColors.on(channel: Channel): Color = when (channel) {
    Channel.EFFORT -> onEffort
    Channel.STRENGTH -> onStrength
    Channel.STREAK -> onStreak
    Channel.RECOVERY -> onRecovery
}

/** The Material icon for an alarm shield glyph (the pure [AlarmGlyph] from core/logic). */
fun AlarmGlyph.icon(): ImageVector = when (this) {
    AlarmGlyph.SHIELD -> Icons.Filled.Shield
    AlarmGlyph.SHIELD_ALERT -> Icons.Filled.Warning
    AlarmGlyph.SHIELD_CHECK -> Icons.Filled.VerifiedUser
}

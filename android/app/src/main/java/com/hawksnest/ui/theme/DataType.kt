package com.hawksnest.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The data display scale — JetBrains Mono for every numeral in the app (weights, reps, timers,
 * counters), so columns of numbers align and readouts feel like instrumentation. Monospace gives
 * tabular figures for free; the slashed zero seals the instrument look.
 *
 * Sits outside the M3 [androidx.compose.material3.Typography] roles on purpose: data display is
 * its own voice, not a heading. Pull via `HawksnestTheme.dataType`.
 */
@Immutable
data class PulseDataTypography(
    /** 14sp — table cells, set rows, inline figures. */
    val numeral: TextStyle,
    /** 17sp — inline emphasis (totals in a row). */
    val numeralLarge: TextStyle,
    /** 20sp — stat tiles. */
    val dataSmall: TextStyle,
    /** 32sp — counters, secondary readouts. */
    val dataMedium: TextStyle,
    /** 44sp — rest ring, hero stats. */
    val dataLarge: TextStyle,
    /** 60sp — the one centerpiece readout on a screen. */
    val dataXL: TextStyle,
)

private const val SLASHED_ZERO = "zero"

fun pulseDataTypography() = PulseDataTypography(
    numeral = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = SLASHED_ZERO,
    ),
    numeralLarge = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, lineHeight = 24.sp, fontFeatureSettings = SLASHED_ZERO,
    ),
    dataSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, fontFeatureSettings = SLASHED_ZERO,
    ),
    dataMedium = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
        fontFeatureSettings = SLASHED_ZERO,
    ),
    dataLarge = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1).sp,
        fontFeatureSettings = SLASHED_ZERO,
    ),
    dataXL = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold,
        fontSize = 60.sp, lineHeight = 64.sp, letterSpacing = (-1.5).sp,
        fontFeatureSettings = SLASHED_ZERO,
    ),
)

val LocalDataTypography = staticCompositionLocalOf { pulseDataTypography() }

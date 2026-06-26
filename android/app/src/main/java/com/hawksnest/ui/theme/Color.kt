package com.hawksnest.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * PULSE palette — a data-forward instrument panel in the original Spotter brand hues.
 *
 * Identity: OLED-dark ink with hairline-stroked panels, and a *channel* color system where each
 * data domain owns a hue — effort (electric blue), strength (indigo/violet), streak (orange),
 * recovery (green). Color always carries meaning; the two brand gradients (blue→indigo hero,
 * orange→amber energy) are reserved for the greeting and primary CTAs. Full Material 3 roles are
 * specified for both themes so anything reading `MaterialTheme.colorScheme` upgrades
 * automatically; the semantic channel layer lives in Pulse.kt.
 *
 * The raw channel seeds are tuned for dark surfaces; light theme MUST use the `*Deep` variants.
 */

// ---- Reference palette ----------------------------------------------------------------------
val PulseInk = Color(0xFF0B0D10)         // dark background
val PulsePanel = Color(0xFF13161B)       // dark surface
val PulsePanelHigh = Color(0xFF1A1E25)   // raised dark surface

val PulseBlue = Color(0xFF4D7CFF)        // effort — volume, work, timers
val PulseIndigo = Color(0xFF7A45F0)      // hero-gradient partner
val PulseViolet = Color(0xFF8B7CFF)      // strength — PRs, loads
val PulseOrange = Color(0xFFFF8A5C)      // streak
val PulseAmber = Color(0xFFF5A623)       // energy-gradient partner
val PulseGreen = Color(0xFF34D399)       // recovery — rest, done, success
val PulseRed = Color(0xFFFF5C5C)         // error (dark)

// Contrast-adapted channel variants for light surfaces (>= 4.5:1 on white).
val PulseBlueDeep = Color(0xFF2A5BFF)
val PulseIndigoDeep = Color(0xFF5B2BE0)
val PulseVioletDeep = Color(0xFF5B2BE0)
val PulseOrangeDeep = Color(0xFFC2410C)
val PulseGreenDeep = Color(0xFF047857)
val PulseRedDeep = Color(0xFFDC2626)

// ---- Light scheme ---------------------------------------------------------------------------
val LightPrimary = PulseBlueDeep
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDEE7FF)
val LightOnPrimaryContainer = Color(0xFF0A2078)

val LightSecondary = PulseGreenDeep
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD8F3E8)
val LightOnSecondaryContainer = Color(0xFF02382A)

val LightTertiary = PulseVioletDeep
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFE6E2FB)
val LightOnTertiaryContainer = Color(0xFF241C66)

val LightError = PulseRedDeep
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFBE0E0)
val LightOnErrorContainer = Color(0xFF5C0E0E)

val LightBackground = Color(0xFFF4F6F8)
val LightOnBackground = Color(0xFF14181D)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF14181D)
val LightSurfaceVariant = Color(0xFFECEEF2)
val LightOnSurfaceVariant = Color(0xFF525A66)
val LightOutline = Color(0xFFC9CDD4)
val LightOutlineVariant = Color(0x1A000000)   // the 1px hairline, as an M3 role too

// ---- Dark scheme ----------------------------------------------------------------------------
val DarkPrimary = PulseBlue
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF1B2440)
val DarkOnPrimaryContainer = Color(0xFFD6E0FF)

val DarkSecondary = PulseGreen
val DarkOnSecondary = Color(0xFF00301F)
val DarkSecondaryContainer = Color(0xFF11332A)
val DarkOnSecondaryContainer = Color(0xFFB9F2DC)

val DarkTertiary = PulseViolet
val DarkOnTertiary = Color(0xFF120A38)
val DarkTertiaryContainer = Color(0xFF231F3F)
val DarkOnTertiaryContainer = Color(0xFFDAD4FF)

val DarkError = PulseRed
val DarkOnError = Color(0xFF3D0202)
val DarkErrorContainer = Color(0xFF4A1414)
val DarkOnErrorContainer = Color(0xFFFFD3D3)

val DarkBackground = PulseInk
val DarkOnBackground = Color(0xFFE7EAF0)
val DarkSurface = PulsePanel
val DarkOnSurface = Color(0xFFE7EAF0)
val DarkSurfaceVariant = PulsePanelHigh
val DarkOnSurfaceVariant = Color(0xFF9AA3B2)
val DarkOutline = Color(0xFF2A2F38)
val DarkOutlineVariant = Color(0x14FFFFFF)    // the 1px hairline, as an M3 role too

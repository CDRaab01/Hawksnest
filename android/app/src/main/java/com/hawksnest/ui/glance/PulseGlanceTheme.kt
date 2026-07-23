package com.hawksnest.ui.glance

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import com.hawksnest.core.logic.Channel
import com.hawksnest.ui.theme.DarkColors
import com.hawksnest.ui.theme.LightColors
import com.hawksnest.ui.theme.color
import com.hawksnest.ui.theme.darkPulseColors
import com.hawksnest.ui.theme.dim
import com.hawksnest.ui.theme.lightPulseColors
import com.hawksnest.ui.theme.on

/**
 * PULSE for the home screen.
 *
 * Glance draws RemoteViews, so none of the in-app theming reaches it: `LocalPulse` is a
 * composition local of the app's composition, and `HawksnestTheme` provides Material 3 roles a
 * widget can't read. What does carry across is the palette itself. `glance-material3` accepts our
 * two existing `ColorScheme`s directly, so the widgets inherit every surface, outline and content
 * color from `ui/theme/Color.kt` — and the channel accents below are read off the same
 * `PulseColors` the app builds. No color originates here; edit `Color.kt` and both surfaces move.
 *
 * Day/night is resolved by the system per-provider, which is why each accent is a
 * `ColorProvider(day, night)` pair rather than one color chosen at build time: a widget is drawn
 * by the launcher, and it must be right in both themes without being redrawn.
 */
private val PulseWidgetColors = ColorProviders(light = LightColors, dark = DarkColors)

private val LightPulse = lightPulseColors()
private val DarkPulse = darkPulseColors()

@Composable
fun PulseGlanceTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = PulseWidgetColors, content = content)
}

/** A channel's base accent — strokes, state text, the active segment's fill. */
fun channelColor(channel: Channel): ColorProvider =
    ColorProvider(day = LightPulse.color(channel), night = DarkPulse.color(channel))

/** A channel's container fill, for tinted panels behind content. */
fun channelDim(channel: Channel): ColorProvider =
    ColorProvider(day = LightPulse.dim(channel), night = DarkPulse.dim(channel))

/** Content color atop a channel's base fill. */
fun onChannel(channel: Channel): ColorProvider =
    ColorProvider(day = LightPulse.on(channel), night = DarkPulse.on(channel))

/** The 1px panel stroke that gives PULSE its instrument-panel edge. */
val hairline: ColorProvider =
    ColorProvider(day = LightPulse.hairline, night = DarkPulse.hairline)

/** The raised panel tone — used for inactive segments and button faces. */
val panelHigh: ColorProvider =
    ColorProvider(day = LightPulse.panelHigh, night = DarkPulse.panelHigh)

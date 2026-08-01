package com.hawksnest.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
// The day/night factory lives in `color`; the type it produces lives in `unit`. Both are needed,
// exactly as `PulseGlanceTheme` needs them, because a widget is drawn in whichever theme the
// launcher is wearing and never gets a chance to redraw when that changes.
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import com.hawksnest.R
import com.hawksnest.core.logic.LedColor
import com.hawksnest.core.logic.ScenePadKeyView

/**
 * The scene pad's own pieces. Deliberately a new file rather than additions to `WidgetPieces.kt`:
 * changing which drawable resolves for an already-placed widget is what wedged live instances the
 * last time this layer was touched, and nothing here is reachable from any widget but this one.
 */

/** The gap between keys — a wall plate's seam, not a layout gutter. */
val SCENE_KEY_GAP = 5.dp

/** How far the LED sits in from the key's top-left corner, as it does on the hardware. */
private val LED_INSET = 7.dp

/** The lamp itself. Small on purpose: on the real plate it is a pinhole, not an indicator light. */
private val LED_SIZE = 7.dp

/**
 * Each LED colour as a day/night pair.
 *
 * Two values per colour because a widget is drawn by the launcher and must be right in both themes
 * without being redrawn — the same reason `channelColor` is a pair. The day values are darkened
 * well past the LED's real hue: an actual yellow or cyan pinhole is invisible against a light
 * panel, and a legible dot is worth more than a literal one.
 *
 * Not routed through PULSE tokens, and this is the one place in the app that is deliberate. These
 * are not the app's palette — they are a reproduction of a specific piece of hardware's indicator
 * colours, and pinning them to PULSE would mean a theme change silently made the widget stop
 * matching the wall.
 */
private fun ledPair(color: LedColor): Pair<Color, Color> = when (color) {
    // "White" reads as a pale grey in daylight; a white dot on a light panel is not a dot.
    LedColor.WHITE -> Color(0xFFB9BEC7) to Color(0xFFFFFFFF)
    LedColor.BLUE -> Color(0xFF1E63D6) to Color(0xFF5B9DFF)
    LedColor.GREEN -> Color(0xFF1B8A3A) to Color(0xFF4ADE80)
    LedColor.RED -> Color(0xFFC62828) to Color(0xFFFF6B6B)
    LedColor.MAGENTA -> Color(0xFFB0219B) to Color(0xFFF472D0)
    LedColor.YELLOW -> Color(0xFFB8860B) to Color(0xFFFDE047)
    LedColor.CYAN -> Color(0xFF0E7C86) to Color(0xFF22D3EE)
}

/**
 * A key's LED, lit or resting.
 *
 * Resting is a colour blended toward the panel rather than the same colour at reduced alpha:
 * Glance tints an `Image` through `setColorFilter`, which discards the alpha channel, so a
 * translucent tint would come back fully opaque and the two states would be identical.
 *
 * On the hardware every LED is simply always on, so dimming the inactive four is the one place
 * this widget departs from the thing it copies. It earns that: the keys carry no labels, so which
 * LED is burning is the only way the pad can show which preset is live.
 */
private fun ledColor(color: LedColor, active: Boolean): ColorProvider {
    val (day, night) = ledPair(color)
    if (active) return ColorProvider(day = day, night = night)
    return ColorProvider(
        day = lerp(day, Color(0xFFF2F3F5), RESTING_BLEND),
        night = lerp(night, Color(0xFF1A1D21), RESTING_BLEND),
    )
}

/** How far a resting LED is blended into the panel. Enough to read as off, not enough to vanish. */
private const val RESTING_BLEND = 0.62f

/**
 * One key of the plate: a blank face with an LED in its top-left corner, exactly as the ZEN32 has
 * it (verified against the hardware, 2026-08-01).
 *
 * **No label, deliberately.** The physical keys have none, and four ellipsised preset names inside
 * ~55dp squares would look nothing like the device the owner asked to see. The live preset is
 * named once, in the header. Each key's [ScenePadKeyView.preset] becomes its content description
 * instead, so the pad is fully navigable by screen reader even though it shows no text.
 *
 * All five keys wear the same face and only their LED changes, which is also how the plate behaves.
 * Lighting the *key* would have meant tinting a button drawable in a channel colour, and that would
 * fight the LED sitting on top of it.
 */
@Composable
fun ScenePadKeyFace(
    view: ScenePadKeyView,
    action: Action?,
    description: String,
    modifier: GlanceModifier = GlanceModifier,
) {
    // Box applies `contentAlignment` to every child — there is no per-child align in Glance — so a
    // key holding anything besides the LED would need that other content to fill the box and
    // centre itself internally. It holds only the LED, so top-start is simply right.
    Box(
        modifier = modifier
            .background(ImageProvider(R.drawable.widget_button_face))
            .let { if (action != null) it.clickable(action) else it }
            .padding(LED_INSET),
        contentAlignment = Alignment.TopStart,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_led_dot),
            contentDescription = description,
            modifier = GlanceModifier.size(LED_SIZE),
            colorFilter = ColorFilter.tint(ledColor(view.led, view.active)),
        )
    }
}

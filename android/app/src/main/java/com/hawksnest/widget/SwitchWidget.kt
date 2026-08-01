package com.hawksnest.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import com.hawksnest.R
import com.hawksnest.core.logic.Channel
import com.hawksnest.core.logic.WIDGET_COMPACT_BUCKET_DP
import com.hawksnest.core.logic.WIDGET_FULL_MIN_HEIGHT_DP
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.WidgetSizeTier
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.core.logic.compactNamePlacement
import com.hawksnest.core.logic.sizeTier
import com.hawksnest.core.logic.switchWidgetView
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.widget.data.WidgetEntryPoint
import com.hawksnest.widget.data.blocker
import com.hawksnest.widget.data.pendingSince
import com.hawksnest.widget.data.snapshot
import com.hawksnest.widget.ui.BlockerBody
import com.hawksnest.widget.ui.WidgetButton
import com.hawksnest.widget.ui.WidgetHeader
import com.hawksnest.widget.ui.WidgetPanel
import kotlinx.serialization.json.Json

/**
 * A paddle switch on the home screen: On above, Off below, the way the wall plate is.
 *
 * ## Why this exists when there is already a light widget
 *
 * `light.*` does not mean "dimmable". The Inovelli VZW30-SN is an on/off switch that Z-Wave
 * exposes as a Multilevel Switch, so HA reports `supported_color_modes: ["brightness"]` and the
 * light widget dutifully offers dim steps to hardware that has no dimmer. `switchWidgetView`
 * ignores those attributes entirely — the kind is the promise — and this widget draws no level.
 *
 * ## Why two halves and not one toggle
 *
 * The light widget's single button has to *say* what the tap will do ("Turn off"), because a
 * toggle whose label is its current state is ambiguous. A paddle has no such problem: the top is
 * on, the bottom is off, and which one is lit tells you where you are. That is also the shape of
 * the thing on the wall, which is the whole point of the request.
 *
 * Compact (< [WIDGET_FULL_MIN_HEIGHT_DP]) has no height to stack two controls, so it lays the same
 * two side by side. It never drops to a single toggle — see the note above [Half], which is a
 * correctness rule and not a layout preference.
 */
class SwitchWidget : GlanceAppWidget() {
    // Responsive, never Exact — see LightWidget for the launcher bug that rules Exact out.
    //
    // Two buckets, not four. The light widget needs width buckets because its toggle label gets
    // shorter when narrow; a paddle's labels are already one word, and `compactNamePlacement`
    // returns INLINE for SWITCH at every width. A third bucket would be a layout nothing can
    // ever select.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(NARROW_WIDTH, WIDGET_COMPACT_BUCKET_DP.dp),
            DpSize(NARROW_WIDTH, WIDGET_FULL_MIN_HEIGHT_DP.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        // Every render kicks a read — the platform's own update period is capped at 30 minutes.
        deps.repository().refreshAsync(WidgetKind.SWITCH, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { SwitchBody(currentState(), json) }
        }
    }
}

@Composable
private fun SwitchBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val view = switchWidgetView(snapshot, System.currentTimeMillis(), prefs.pendingSince())
    val retry = actionRunCallback<WidgetRefreshAction>(widgetParams(WidgetKind.SWITCH))

    val size = LocalSize.current
    val compact = sizeTier(size.height.value.toInt()) == WidgetSizeTier.COMPACT

    WidgetPanel(compact = compact, accent = Channel.STREAK.takeIf { view.on && snapshot != null }) {
        if (snapshot == null) {
            BlockerBody(blocker ?: WidgetBlocker.NOT_CONFIGURED, retry, R.drawable.ic_glyph_power)
        } else {
            // Same rule as the light: keep the last reading through an outage, never silently.
            val note = blocker?.let { blockerCopy(it).headline } ?: view.staleness
            WidgetHeader(
                name = view.name,
                detail = view.stateLabel,
                icon = R.drawable.ic_glyph_power,
                accent = if (view.on) Channel.STREAK else null,
                pending = view.pending,
                note = note,
                compact = compact,
                namePlacement = compactNamePlacement(
                    WidgetKind.SWITCH,
                    size.width.value.toInt(),
                    size.height.value.toInt(),
                ),
            )
            Spacer(modifier = GlanceModifier.height(if (compact) 4.dp else 8.dp))
            // Both halves, at every size. Compact lays them side by side instead of stacked
            // because a one-row widget has no height to split, but it never drops to a single
            // control — the note above `Half` says why that is correctness, not taste.
            if (compact) {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    // Bare turn_on: HA restores the device's own last level, which is what the
                    // top of the physical paddle does. Never a brightness.
                    Half("On", "turn_on", view.on, view.controllable, GlanceModifier.defaultWeight())
                    Spacer(modifier = GlanceModifier.width(PADDLE_GAP))
                    Half("Off", "turn_off", !view.on, view.controllable, GlanceModifier.defaultWeight())
                }
            } else {
                Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    Half(
                        "On", "turn_on", view.on, view.controllable,
                        GlanceModifier.fillMaxWidth().defaultWeight(),
                    )
                    Spacer(modifier = GlanceModifier.height(PADDLE_GAP))
                    Half(
                        "Off", "turn_off", !view.on, view.controllable,
                        GlanceModifier.fillMaxWidth().defaultWeight(),
                    )
                }
            }
        }
    }
}

/*
 * ## Why there is no single-toggle layout anywhere in this widget
 *
 * The compact tier used to collapse to one button labelled with the direction the switch was not
 * already in: "Off" when it believed the light was on, "On" when it believed it was off. That is
 * the ordinary way to draw a toggle, and here it was wrong, because a widget's belief can be
 * arbitrarily out of date. Nothing redraws the home screen when someone flips the switch on the
 * wall — the live socket only pushes while the app is running, and the render-triggered refresh
 * only fires when the widget is redrawn for some other reason.
 *
 * So the failure was: turn the light on at the wall, and the widget still holds "off", still
 * offers only "On", and there is literally no way to turn it off from the phone. The one control
 * it showed pointed the wrong way, and the owner hit exactly that.
 *
 * Both directions, always, makes the widget correct no matter how stale it is: the tap says what
 * it does rather than depending on what the widget thinks. Pressing On while already on is a
 * harmless no-op at HA, and the confirming read that follows every tap repairs the stale reading
 * as a side effect. That is worth far more than the ambiguity a single toggle also carried — the
 * old layout showed a filled orange button reading "Off" while its own header read "On", which is
 * unreadable even when the state behind it is right.
 */

/**
 * One half of the paddle. [modifier] carries the weight, because `defaultWeight()` is a
 * scope-specific extension and this is called from both a Row and a Column.
 */
@Composable
private fun Half(
    label: String,
    service: String,
    filled: Boolean,
    controllable: Boolean,
    modifier: GlanceModifier,
) {
    WidgetButton(
        label = label,
        action = actionRunCallback<WidgetServiceAction>(
            widgetParams(WidgetKind.SWITCH, service = service)
        ).takeIf { controllable },
        modifier = modifier,
        accent = Channel.STREAK,
        filled = filled,
        fillHeight = true,
    )
}

/**
 * The seam between the two halves. Deliberately tight — a real paddle is one piece split by a
 * hairline, and 4dp is as close as two separate button drawables get to that.
 */
private val PADDLE_GAP = 4.dp

/** The narrow bucket — the provider's own minimum width. */
private val NARROW_WIDTH = 110.dp

class SwitchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SwitchWidget()
}

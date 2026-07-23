package com.hawksnest.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import com.hawksnest.core.logic.Channel
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.WidgetSizeTier
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.core.logic.compactShowsName
import com.hawksnest.core.logic.dimDown
import com.hawksnest.core.logic.dimUp
import com.hawksnest.core.logic.lightWidgetView
import com.hawksnest.core.logic.sizeTier
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.ui.glance.channelColor
import com.hawksnest.ui.glance.panelHigh
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
 * A light or switch on the home screen: tap to toggle, and — where the light can actually dim —
 * two step buttons.
 *
 * The in-app control is `LightPillar`, whose whole surface is a drag-to-dim gesture. Glance has no
 * drag and no slider; RemoteViews only offer taps. Rather than fake a slider that can't be
 * dragged, the dimmer steps along `WIDGET_DIM_STOPS` — a ladder geared like a real dimmer, tight
 * at the bottom where the eye notices and wide at the top where it doesn't. Each step commits
 * through the same `dimCommit` the pillar uses on release: one gesture, one service call.
 *
 * A thin bar under the name shows the level. It is deliberately not tappable: a home screen is
 * ~250dp wide, so a bar split into enough zones to beat the step buttons would have targets around
 * 20dp, and this way the level is visible without adding a control to mis-hit.
 */
class LightWidget : GlanceAppWidget() {
    // Exact, so the dim controls can stand down on a widget too narrow to hold them honestly.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        // Every render kicks a read. This is the widget's main freshness mechanism — the platform's
        // own update period is capped at 30 minutes, which is far too coarse to trust on its own.
        deps.repository().refreshAsync(WidgetKind.LIGHT, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { LightBody(currentState(), json) }
        }
    }
}

@Composable
private fun LightBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val view = lightWidgetView(snapshot, System.currentTimeMillis(), prefs.pendingSince())
    val retry = actionRunCallback<WidgetRefreshAction>(widgetParams(WidgetKind.LIGHT))

    val size = LocalSize.current
    val compact = sizeTier(size.height.value.toInt()) == WidgetSizeTier.COMPACT

    // A lit lamp warms its own rim; an off one sits quiet behind the plain hairline.
    WidgetPanel(compact = compact, accent = Channel.STREAK.takeIf { view.on && snapshot != null }) {
        if (snapshot == null) {
            // Nothing has ever been read. Say why, and make the whole face a retry.
            BlockerBody(blocker ?: WidgetBlocker.NOT_CONFIGURED, retry)
        } else {
            // A light may keep showing its last reading while HA is unreachable — a lamp drawn
            // wrong is a cosmetic error, not a security one — but never silently. The note line
            // carries either the failure or the reading's age.
            val note = blocker?.let { blockerCopy(it).headline } ?: view.staleness
            WidgetHeader(
                name = view.name,
                detail = view.stateLabel,
                accent = if (view.on) Channel.STREAK else null,
                pending = view.pending,
                note = note,
                compact = compact,
                showName = compactShowsName(WidgetKind.LIGHT),
            )
            // The level, as a level. Read-only — it costs no touch target and gives the widget the
            // one thing the old ±20% buttons couldn't: somewhere to see what you just changed.
            // First thing to go when the widget is squeezed: the header already says the percent.
            if (view.dimmable && view.on && !compact) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                LinearProgressIndicator(
                    progress = view.pct / 100f,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = channelColor(Channel.STREAK),
                    backgroundColor = panelHigh,
                )
            }
            Spacer(modifier = GlanceModifier.height(if (compact) 4.dp else 8.dp))
            val width = size.width
            Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                val toggle = if (view.on) "turn_off" else "turn_on"
                WidgetButton(
                    // The header already states what the light *is*; this button says what the tap
                    // will do. On a narrow widget it says it in one word so the dim steps still fit.
                    label = when {
                        width < VERBOSE_MIN_WIDTH -> if (view.on) "Off" else "On"
                        view.on -> "Turn off"
                        else -> "Turn on"
                    },
                    action = if (view.controllable) {
                        actionRunCallback<WidgetServiceAction>(
                            widgetParams(WidgetKind.LIGHT, service = toggle)
                        )
                    } else {
                        null
                    },
                    modifier = GlanceModifier.defaultWeight(),
                    accent = Channel.STREAK,
                    filled = view.on,
                    fillHeight = compact,
                )
                if (view.dimmable && view.controllable) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    DimButton("−", dimDown(view.pct), compact)
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    DimButton("+", dimUp(view.pct), compact)
                }
            }
        }
    }
}

@Composable
private fun DimButton(label: String, targetPct: Int, compact: Boolean) {
    WidgetButton(
        label = label,
        fillHeight = compact,
        action = actionRunCallback<WidgetServiceAction>(
            // `dimCommit` maps a level to a call; at these clamped levels that is always turn_on
            // with a brightness percent — stepping down never reaches the "off" case.
            widgetParams(WidgetKind.LIGHT, service = "turn_on", brightnessPct = targetPct)
        ),
        // 44dp square keeps both steps on a three-cell widget while staying a real touch target
        // (WidgetButton fixes the height at 48dp).
        modifier = GlanceModifier.width(44.dp),
        accent = Channel.STREAK,
    )
}

/** Below this the toggle drops to a single word so the dim steps keep their room. */
private val VERBOSE_MIN_WIDTH = 220.dp

class LightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LightWidget()
}

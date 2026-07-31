package com.hawksnest.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hawksnest.R
import com.hawksnest.core.logic.WIDGET_COMPACT_BUCKET_DP
import com.hawksnest.core.logic.WIDGET_FULL_MIN_HEIGHT_DP
import com.hawksnest.core.logic.WIDGET_NAME_MIN_WIDTH_DP
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.TemperatureWidgetView
import com.hawksnest.core.logic.WidgetSizeTier
import com.hawksnest.core.logic.sizeTier
import com.hawksnest.core.logic.temperatureWidgetView
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.ui.glance.channelColor
import com.hawksnest.widget.data.WidgetEntryPoint
import com.hawksnest.widget.data.blocker
import com.hawksnest.widget.data.snapshot
import com.hawksnest.widget.data.tempThresholds
import com.hawksnest.widget.ui.BlockerBody
import com.hawksnest.widget.ui.WidgetPanel
import com.hawksnest.widget.ui.readAtLabel
import kotlinx.serialization.json.Json

/**
 * A room's temperature on the home screen, coloured by whether it's comfortable.
 *
 * The only read-only widget in the set — there is nothing to tap, so none of the
 * pending/confirm/echo machinery the control widgets need applies here. What it
 * borrows instead is the *light* widget's staleness stance rather than the lock's:
 * an old reading is still shown, with its age, because a room's temperature doesn't
 * change the way a door does and an hour-old 70° is still worth knowing. A lock
 * hides a stale state because "Locked" when it isn't is dangerous; there is no
 * equivalent danger here.
 *
 * Thresholds are per instance (see `WidgetKeys.TEMP_*`), so a nursery and a garage
 * can disagree about what "comfortable" means. The band never converts units — it
 * compares the sensor's own number against thresholds entered in that same unit,
 * which is why a °C household needs no code change.
 */
class TemperatureWidget : GlanceAppWidget() {
    // Two buckets is enough: unlike the control widgets there are no buttons whose
    // arrangement changes, only how large the number can be. The wide bucket exists
    // so a three-cell placement can print the room name beside the reading.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(NARROW_WIDTH, WIDGET_COMPACT_BUCKET_DP.dp),
            DpSize(WIDGET_NAME_MIN_WIDTH_DP.dp, WIDGET_COMPACT_BUCKET_DP.dp),
            DpSize(NARROW_WIDTH, WIDGET_FULL_MIN_HEIGHT_DP.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        deps.repository().refreshAsync(WidgetKind.TEMPERATURE, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { TemperatureBody(currentState(), json) }
        }
    }
}

@Composable
private fun TemperatureBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val (coldBelow, warmAbove, hotAbove) = prefs.tempThresholds()
    val view = temperatureWidgetView(
        snapshot = snapshot,
        nowMs = System.currentTimeMillis(),
        coldBelow = coldBelow,
        warmAbove = warmAbove,
        hotAbove = hotAbove,
    )
    val retry = actionRunCallback<WidgetRefreshAction>(widgetParams(WidgetKind.TEMPERATURE))

    val size = LocalSize.current
    val compact = sizeTier(size.height.value.toInt()) == WidgetSizeTier.COMPACT

    WidgetPanel(compact = compact, accent = view.channel, alert = view.alert) {
        if (blocker != null) {
            BlockerBody(blocker, retry, R.drawable.ic_glyph_thermometer)
        } else {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    view.name,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(if (compact) 2.dp else 6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        view.reading ?: "—",
                        style = TextStyle(
                            // The colour IS the answer, so it belongs on the number
                            // rather than a chip beside it.
                            color = bandColor(view),
                            fontSize = if (compact) 28.sp else 40.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    if (view.reading != null) {
                        Text(
                            view.unit,
                            style = TextStyle(
                                color = bandColor(view),
                                fontSize = if (compact) 13.sp else 16.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                // Words as well as colour: colour blindness, and a glance in sunlight.
                Text(
                    view.label,
                    style = TextStyle(
                        color = bandColor(view),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                // Only ever printed when the reading is genuinely old — an
                // always-on timestamp would train the eye to ignore it.
                view.staleness?.let {
                    Text(
                        it,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
                if (!compact) {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        readAtLabel(snapshot?.fetchedAtMs) ?: "",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The colour the reading wears: its channel, or the error red for the "too hot"
 * band, which has no channel because PULSE has no red one.
 */
@Composable
private fun bandColor(view: TemperatureWidgetView): ColorProvider = when {
    view.alert -> GlanceTheme.colors.error
    view.channel != null -> channelColor(view.channel)
    else -> GlanceTheme.colors.onSurfaceVariant
}

/** The narrow bucket — the provider's own minimum width. */
private val NARROW_WIDTH = 110.dp

class TemperatureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TemperatureWidget()
}

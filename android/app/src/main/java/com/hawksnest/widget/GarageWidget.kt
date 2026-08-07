package com.hawksnest.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import com.hawksnest.R
import com.hawksnest.core.logic.GaragePhase
import com.hawksnest.core.logic.WIDGET_COMPACT_BUCKET_DP
import com.hawksnest.core.logic.WIDGET_COMPACT_TALL_BUCKET_DP
import com.hawksnest.core.logic.WIDGET_FULL_MIN_HEIGHT_DP
import com.hawksnest.core.logic.WIDGET_MIN_WIDTH_DP
import com.hawksnest.core.logic.WIDGET_NAME_MIN_WIDTH_DP
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.WidgetSizeTier
import com.hawksnest.core.logic.compactNamePlacement
import com.hawksnest.core.logic.garageWidgetView
import com.hawksnest.core.logic.sizeTier
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.widget.data.WidgetEntryPoint
import com.hawksnest.widget.data.blocker
import com.hawksnest.widget.data.entityId
import com.hawksnest.widget.data.pendingSince
import com.hawksnest.widget.data.snapshot
import com.hawksnest.widget.ui.BlockerBody
import com.hawksnest.widget.ui.WidgetButton
import com.hawksnest.widget.ui.WidgetHeader
import com.hawksnest.widget.ui.WidgetPanel
import com.hawksnest.widget.ui.openApp
import com.hawksnest.widget.ui.openEntity
import com.hawksnest.widget.ui.readAtLabel
import kotlinx.serialization.json.Json

/**
 * Whether a garage door is open, on the home screen.
 *
 * THE READ-ONLY CASE IS THE MAIN CASE HERE, which is what separates this from the lock. The doors
 * in this house are watched by Ecolink tilt sensors — `binary_sensor` entities that report a
 * position and accept nothing — and there is no opener fitted. So on this hardware the widget
 * draws no button at all. Not a greyed one: a control that can never work is worse than an honest
 * status card, and it would spend its life inviting a tap that does nothing.
 *
 * That makes the layout closer to the temperature widget than the lock: the WHOLE panel is the tap
 * target and opens the entity's history, because with no button there is no gesture to collide
 * with, and a status card you can only tap in its top-left corner is a worse version of being
 * tappable at all.
 *
 * When the configured entity IS a `cover` — an opener fitted later — [garageWidgetView] returns a
 * service and this draws the button, at which point the panel keeps its deep-link and the button
 * sits under it exactly as the lock's does. That path has never run against real hardware.
 *
 * STALENESS: this widget keeps an old reading rather than blanking it, unlike the lock. A tilt
 * sensor transmits only when the door moves, so "nothing heard since Tuesday" describes a door
 * that has not moved since Tuesday — not a reading that has expired. The read-at stamp still
 * prints beside the state, so a frame drawn hours ago cannot pretend to be current. The reasoning
 * lives on [com.hawksnest.core.logic.widgetKeepsStaleReading].
 */
class GarageWidget : GlanceAppWidget() {
    // The lock's bucket set, minus nothing: this widget has the same two shapes (header alone when
    // read-only, header over one full-width button on a cover) and the same narrow floor.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(NARROW_WIDTH, WIDGET_COMPACT_BUCKET_DP.dp),
            DpSize(WIDGET_NAME_MIN_WIDTH_DP.dp, WIDGET_COMPACT_BUCKET_DP.dp),
            DpSize(NARROW_WIDTH, WIDGET_COMPACT_TALL_BUCKET_DP.dp),
            DpSize(NARROW_WIDTH, WIDGET_FULL_MIN_HEIGHT_DP.dp),
            DpSize(WIDGET_MIN_WIDTH_DP.dp, WIDGET_COMPACT_BUCKET_DP.dp),
            DpSize(WIDGET_MIN_WIDTH_DP.dp, WIDGET_COMPACT_TALL_BUCKET_DP.dp),
            DpSize(WIDGET_MIN_WIDTH_DP.dp, WIDGET_FULL_MIN_HEIGHT_DP.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        deps.repository().refreshAsync(WidgetKind.GARAGE, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { GarageBody(currentState(), json) }
        }
    }
}

@Composable
private fun GarageBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val view = garageWidgetView(
        snapshot = snapshot,
        nowMs = System.currentTimeMillis(),
        pendingSinceMs = prefs.pendingSince(),
    )
    val retry = actionRunCallback<WidgetRefreshAction>(widgetParams(WidgetKind.GARAGE))

    val size = LocalSize.current
    val compact = sizeTier(size.height.value.toInt()) == WidgetSizeTier.COMPACT

    // The glyph carries the answer as much as the colour does, for the same reason the
    // temperature widget prints a band word: colour alone fails in sunlight and for the
    // colour-blind. An open door and a shut one are different shapes, not one shape in two inks.
    val glyph = when (view.phase) {
        GaragePhase.CLOSED, GaragePhase.CLOSING -> R.drawable.ic_glyph_garage
        else -> R.drawable.ic_glyph_garage_open
    }

    WidgetPanel(compact = compact, accent = view.channel) {
        if (blocker != null) {
            BlockerBody(blocker, retry, R.drawable.ic_glyph_garage)
        } else {
            // Read the id from the CONFIGURED entity rather than the snapshot, so the tap works on
            // the very first frame — before any reading has landed. Falls back to opening the app
            // rather than routing to a malformed `entity/` URL.
            val open = prefs.entityId()?.let { openEntity(it) } ?: openApp()
            Column(modifier = GlanceModifier.fillMaxWidth().clickable(open)) {
                WidgetHeader(
                    name = view.name,
                    detail = view.label,
                    icon = glyph,
                    accent = view.channel,
                    pending = view.pending,
                    // The read-at stamp, exactly as the lock prints it. This widget needs it MORE
                    // than the lock does, not less: it deliberately keeps stale readings, so the
                    // timestamp is the only thing stopping a frame from four hours ago reading as
                    // a statement about now.
                    note = readAtLabel(view.readAtMs),
                    compact = compact,
                    namePlacement = compactNamePlacement(
                        WidgetKind.GARAGE,
                        size.width.value.toInt(),
                        size.height.value.toInt(),
                    ),
                    // Same destination as the panel it sits on — otherwise the header would be a
                    // hole in the middle of the tap target that went somewhere else.
                    onClick = open,
                )
                // Only a driveable door gets a button. On a tilt sensor `actionLabel` is null and
                // this whole branch disappears, leaving the header to fill the panel.
                if (view.actionLabel != null && view.service != null) {
                    if (!compact) Spacer(modifier = GlanceModifier.defaultWeight())
                    Spacer(modifier = GlanceModifier.height(if (compact) 4.dp else 8.dp))
                    WidgetButton(
                        label = view.actionLabel,
                        action = actionRunCallback<WidgetServiceAction>(
                            widgetParams(WidgetKind.GARAGE, service = view.service)
                        ),
                        modifier = GlanceModifier.fillMaxWidth(),
                        accent = view.actionChannel,
                        tinted = view.known,
                        icon = glyph,
                    )
                }
            }
        }
    }
}

/** The narrow bucket — the provider's own minimum width. */
private val NARROW_WIDTH = 110.dp

class GarageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarageWidget()
}

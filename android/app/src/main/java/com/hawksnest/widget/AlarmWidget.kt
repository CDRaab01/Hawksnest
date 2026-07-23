package com.hawksnest.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import com.hawksnest.core.logic.ARM_BUTTONS
import com.hawksnest.core.logic.ArmTap
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.alarmWidgetView
import com.hawksnest.core.logic.armTap
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.widget.data.WidgetEntryPoint
import com.hawksnest.widget.data.blocker
import com.hawksnest.widget.data.confirmService
import com.hawksnest.widget.data.confirmSince
import com.hawksnest.widget.data.pendingSince
import com.hawksnest.widget.data.snapshot
import com.hawksnest.widget.ui.BlockerBody
import com.hawksnest.widget.ui.WidgetButton
import com.hawksnest.widget.ui.WidgetHeader
import com.hawksnest.widget.ui.WidgetPanel
import com.hawksnest.widget.ui.readAtLabel
import kotlinx.serialization.json.Json

/**
 * The Ring alarm on the home screen: Off / Home / Away, the same three segments as the in-app
 * `ArmSegments`, built from the same [ARM_BUTTONS] list so the two controls can never drift.
 *
 * There is nothing Ring-specific here, deliberately — the base station is an ordinary
 * `alarm_control_panel` to Home Assistant, and treating it as one is what lets this widget work
 * with whatever panel the house has.
 *
 * Arming is one tap. Disarming takes two, for the same reason unlocking does on the lock widget:
 * it is the direction that removes protection, and a home screen is a surface things get tapped
 * on by accident. Transitional states (`arming`, exit delays) are shown as themselves rather than
 * smoothed over, and a reading older than a minute is not shown at all.
 */
class AlarmWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        deps.repository().refreshAsync(WidgetKind.ALARM, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { AlarmBody(currentState(), json) }
        }
    }
}

@Composable
private fun AlarmBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val view = alarmWidgetView(
        snapshot = snapshot,
        nowMs = System.currentTimeMillis(),
        pendingSinceMs = prefs.pendingSince(),
        confirmService = prefs.confirmService(),
        confirmSinceMs = prefs.confirmSince(),
    )
    val retry = actionRunCallback<WidgetRefreshAction>(widgetParams(WidgetKind.ALARM))

    WidgetPanel {
        if (blocker != null) {
            BlockerBody(blocker, retry)
        } else {
            WidgetHeader(
                name = view.name,
                detail = view.label,
                accent = view.channel,
                pending = view.pending,
                // See LockWidget: the read time keeps a persisted frame honest.
                note = readAtLabel(view.readAtMs),
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                ARM_BUTTONS.forEachIndexed { index, button ->
                    if (index > 0) Spacer(modifier = GlanceModifier.width(6.dp))
                    val tap = armTap(view, button)
                    val confirming = view.confirmingService == button.service
                    WidgetButton(
                        // A trailing question mark is the whole confirm affordance: three segments
                        // on a phone-width widget leave no room for a word like "Confirm".
                        label = if (confirming) "${button.label}?" else button.label,
                        action = when (tap) {
                            is ArmTap.Send -> actionRunCallback<WidgetServiceAction>(
                                widgetParams(WidgetKind.ALARM, service = tap.service)
                            )
                            is ArmTap.Confirm -> actionRunCallback<WidgetConfirmAction>(
                                widgetParams(WidgetKind.ALARM, service = tap.service)
                            )
                            // Ignored either because the panel is already in this state, or
                            // because a command is still settling. Unknown state offers a retry.
                            ArmTap.Ignore -> if (!view.known) retry else null
                        },
                        modifier = GlanceModifier.defaultWeight(),
                        accent = view.channel,
                        filled = view.activeState == button.state || confirming,
                    )
                }
            }
        }
    }
}

class AlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AlarmWidget()
}

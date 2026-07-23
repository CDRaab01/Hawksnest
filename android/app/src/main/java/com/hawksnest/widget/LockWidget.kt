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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.lockWidgetView
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.widget.data.WidgetEntryPoint
import com.hawksnest.widget.data.blocker
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
 * A lock on the home screen. Locking takes one tap; unlocking takes two.
 *
 * The in-app control is `LockVault`, where unlocking costs a deliberate slide — a gesture chosen
 * so a pocket or a misplaced thumb can't open the front door. Glance can't draw a slide track, so
 * the widget substitutes the nearest equivalent deliberate act: the first tap arms
 * "Tap again to unlock" and the second sends it, and the arming lapses on its own after five
 * seconds so a widget left sitting on the home screen is never one tap from an open door.
 *
 * Everything else follows the app's non-optimistic lock invariant exactly. The face never shows
 * the tap the owner made — only what Home Assistant has confirmed. A reading older than a minute
 * is not shown at all (`securityStateFresh`): a widget is a picture drawn at an unknown time by a
 * process that may since have died, and "Locked" is the one word it must never guess.
 */
class LockWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        deps.repository().refreshAsync(WidgetKind.LOCK, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { LockBody(currentState(), json) }
        }
    }
}

@Composable
private fun LockBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val view = lockWidgetView(
        snapshot = snapshot,
        nowMs = System.currentTimeMillis(),
        pendingSinceMs = prefs.pendingSince(),
        confirmSinceMs = prefs.confirmSince(),
    )
    val params = widgetParams(WidgetKind.LOCK)
    val retry = actionRunCallback<WidgetRefreshAction>(params)

    WidgetPanel {
        // A lock with a live problem shows the problem, full stop — no half-state behind a banner.
        // `WidgetRepository` has already masked the stored reading, so there is nothing to leak.
        if (blocker != null) {
            BlockerBody(blocker, retry)
        } else {
            WidgetHeader(
                name = view.name,
                detail = view.label,
                accent = view.channel,
                pending = view.pending,
                // Always stamped: this frame may still be on the home screen long after the
                // reading behind it expired, and it must say so itself.
                note = readAtLabel(view.readAtMs),
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            WidgetButton(
                label = view.actionLabel,
                action = when {
                    // First tap of an unlock: arm the confirmation, send nothing.
                    view.armsConfirm -> actionRunCallback<WidgetConfirmAction>(
                        widgetParams(WidgetKind.LOCK, service = "unlock")
                    )
                    view.service != null -> actionRunCallback<WidgetServiceAction>(
                        widgetParams(WidgetKind.LOCK, service = view.service)
                    )
                    // Unknown or expired: the button becomes the retry.
                    !view.known -> retry
                    else -> null
                },
                modifier = GlanceModifier.fillMaxWidth(),
                accent = view.actionChannel,
                filled = view.confirming,
            )
        }
    }
}

class LockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LockWidget()
}

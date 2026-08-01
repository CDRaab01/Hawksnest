package com.hawksnest.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
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
import com.hawksnest.core.logic.SCENE_PAD_HEADER_MIN_DP
import com.hawksnest.core.logic.ScenePadKey
import com.hawksnest.core.logic.ScenePadKeyView
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.core.logic.scenePadShowsHeader
import com.hawksnest.core.logic.scenePadView
import com.hawksnest.ui.glance.PulseGlanceTheme
import com.hawksnest.widget.data.ActionTarget
import com.hawksnest.widget.data.WidgetEntryPoint
import com.hawksnest.widget.data.blocker
import com.hawksnest.widget.data.companionEntityId
import com.hawksnest.widget.data.pendingSince
import com.hawksnest.widget.data.room
import com.hawksnest.widget.data.sceneLeds
import com.hawksnest.widget.data.scenePresets
import com.hawksnest.widget.data.snapshot
import com.hawksnest.widget.ui.BlockerBody
import com.hawksnest.widget.ui.SCENE_KEY_GAP
import com.hawksnest.widget.ui.ScenePadKeyFace
import com.hawksnest.widget.ui.WidgetHeader
import com.hawksnest.widget.ui.WidgetPanel
import kotlinx.serialization.json.Json

/**
 * A Zooz ZEN32 wall controller, on the home screen.
 *
 * ## The layout is the device's, verified rather than assumed
 *
 * Large relay key across the top, four small keys in a 2×2 grid beneath it, an LED pinhole in each
 * key's upper-left corner. That was checked against the hardware on 2026-08-01; the plan this was
 * built from had the relay at the bottom, which would have put every constant here upside down.
 *
 * ## Two entities, and only one of them is read
 *
 * The pad *draws* the WLED preset selector — that entity's state is the live preset, which is what
 * lights one of the four LEDs. It also *drives* the controller's own relay, a second entity stored
 * alongside and never read (`ActionTarget.COMPANION`). So the relay key fires but shows no state:
 * the known gap, and closing it means holding a second reading per widget, which is a change to the
 * persisted format and belongs on its own.
 *
 * ## What a key press actually does
 *
 * The physical key emits a Z-Wave Central Scene event, which a Home Assistant automation turns
 * into `select.select_option`. The widget cannot emit that event — Central Scene notifications come
 * from the device and HA has no service to synthesise one — so it calls the service directly. The
 * two are identical today and will diverge silently the day that automation grows a second action.
 * The durable fix is an HA script per key as the single definition of what the key *means*, with
 * both the automation and this widget pointed at it; that is a reconfiguration, not a rewrite.
 */
class ScenePadWidget : GlanceAppWidget() {
    // Two buckets. The only thing that changes with size is whether the header fits, so a third
    // would be a layout the framework could never have reason to select. Responsive, never Exact
    // — see LightWidget for the launcher bug that rules Exact out.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(PAD_MIN_WIDTH, PAD_MIN_HEIGHT),
            DpSize(PAD_MIN_WIDTH, SCENE_PAD_HEADER_MIN_DP.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = WidgetEntryPoint.get(context)
        deps.repository().refreshAsync(WidgetKind.SCENE_PAD, id)
        val json = deps.json()
        provideContent {
            PulseGlanceTheme { ScenePadBody(currentState(), json) }
        }
    }
}

@Composable
private fun ScenePadBody(prefs: Preferences, json: Json) {
    val snapshot = prefs.snapshot(json)
    val blocker = prefs.blocker()
    val view = scenePadView(
        snapshot = snapshot,
        presets = prefs.scenePresets(),
        leds = prefs.sceneLeds(),
        relayEntityId = prefs.companionEntityId(),
        room = prefs.room(),
        nowMs = System.currentTimeMillis(),
        pendingSinceMs = prefs.pendingSince(),
    )
    val retry = actionRunCallback<WidgetRefreshAction>(widgetParams(WidgetKind.SCENE_PAD))

    WidgetPanel {
        if (snapshot == null) {
            BlockerBody(blocker ?: WidgetBlocker.NOT_CONFIGURED, retry, R.drawable.ic_glyph_power)
        } else {
            if (scenePadShowsHeader(LocalSize.current.height.value.toInt())) {
                WidgetHeader(
                    name = view.name,
                    detail = view.presetLabel,
                    icon = R.drawable.ic_glyph_power,
                    pending = view.pending,
                    note = blocker?.let { blockerCopy(it).headline } ?: view.staleness,
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
            }
            // Relay above, grid below, split evenly — which lands within a couple of percent of
            // the real plate's proportions (the relay is a shade under half of it). The grid needs
            // its own Column to get that split: Glance's `defaultWeight` takes no value, so three
            // siblings would divide the plate into equal thirds and squash the relay.
            ScenePadKeyFace(
                view = ScenePadKeyView(
                    key = ScenePadKey.RELAY,
                    preset = null,
                    led = view.relayLed,
                    // The relay's own state is not read (see the class KDoc), so its LED sits at
                    // rest rather than claiming a state the widget cannot know. Lit would be a
                    // guess, and a guess about a load that may be a light in another room.
                    active = false,
                    enabled = view.relayEnabled,
                ),
                action = actionRunCallback<WidgetServiceAction>(
                    widgetParams(
                        WidgetKind.SCENE_PAD,
                        service = "toggle",
                        target = ActionTarget.COMPANION,
                    )
                ).takeIf { view.relayEnabled },
                description = "Relay",
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
            Spacer(modifier = GlanceModifier.height(SCENE_KEY_GAP))
            Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                KeyRow(view.keys.getOrNull(0), view.keys.getOrNull(1))
                Spacer(modifier = GlanceModifier.height(SCENE_KEY_GAP))
                KeyRow(view.keys.getOrNull(2), view.keys.getOrNull(3))
            }
        }
    }
}

/** Two small keys side by side — half the grid. */
@Composable
private fun androidx.glance.layout.ColumnScope.KeyRow(
    left: ScenePadKeyView?,
    right: ScenePadKeyView?,
) {
    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
        SmallKey(left)
        Spacer(modifier = GlanceModifier.width(SCENE_KEY_GAP))
        SmallKey(right)
    }
}

@Composable
private fun androidx.glance.layout.RowScope.SmallKey(view: ScenePadKeyView?) {
    if (view == null) return
    ScenePadKeyFace(
        view = view,
        action = view.preset?.let { preset ->
            actionRunCallback<WidgetServiceAction>(
                // Straight to the select. Pressing the key whose preset is already live re-selects
                // it, exactly as pressing the wall key does — WLED restarts the effect.
                widgetParams(WidgetKind.SCENE_PAD, service = "select_option", option = preset)
            )
        }.takeIf { view.enabled },
        // The keys show no text, so this is the only thing that names them. "Key 2" rather than
        // silence when the slot is empty, so a half-configured pad is still navigable.
        description = view.preset ?: "Key ${view.key.ordinal}",
        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
    )
}

/** The provider's own minimum — the plate needs this much before its keys stop being slivers. */
private val PAD_MIN_WIDTH = 150.dp
private val PAD_MIN_HEIGHT = 150.dp

class ScenePadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScenePadWidget()
}

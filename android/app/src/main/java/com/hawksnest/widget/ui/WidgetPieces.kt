package com.hawksnest.widget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawksnest.MainActivity
import com.hawksnest.core.logic.Channel
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.ui.glance.channelColor
import com.hawksnest.ui.glance.hairline
import com.hawksnest.ui.glance.onChannel
import com.hawksnest.ui.glance.panelHigh
import com.hawksnest.ui.navigation.Screen
import com.hawksnest.widget.WidgetConfigActivity
import java.util.Date

/**
 * The pieces every Hawksnest widget is built from, so the three of them read as one family and as
 * a continuation of the in-app panels: a hairline-stroked surface, a name over a state line, and
 * one honest sentence when there is nothing to control.
 */

/** The minimum height for anything tappable. Enforced here so no widget can quietly go below it. */
private val TOUCH_TARGET = 48.dp

/** Opens the app. Every widget offers this on its title, so a tap always leads somewhere. */
fun openApp(): Action = actionStartActivity<MainActivity>()

/** Opens the app on Settings — where the credential problems a widget can hit are actually fixed. */
@Composable
private fun openSettings(): Action = actionStartActivity(
    Intent(LocalContext.current, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_START_ROUTE, Screen.Settings.route)
)

/**
 * Re-opens this widget's own configuration screen. The launcher only shows it once, when the
 * widget is placed, so a widget whose entity has since been deleted from Home Assistant would
 * otherwise be stuck — this makes "Tap to pick another" true.
 */
@Composable
private fun openConfig(): Action {
    val context = LocalContext.current
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(LocalGlanceId.current)
    return actionStartActivity(
        Intent(context, WidgetConfigActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun WidgetPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            // Arbitrary corner radii are an API 31+ capability; below that this is ignored and the
            // widget draws square, which is a cosmetic loss on devices this app will never see.
            .cornerRadius(20.dp)
            .padding(12.dp),
        content = content,
    )
}

/**
 * Name over state. The state line wears the channel color when there is one — green for a locked
 * door, blue for an armed panel — which is the same signal the in-app cards give.
 */
@Composable
fun WidgetHeader(
    name: String,
    detail: String,
    accent: Channel? = null,
    pending: Boolean = false,
    /**
     * Appended after the state, and always a caveat on it: how old a light's reading is, when a
     * lock was last read, or what went wrong. Never decoration.
     */
    note: String? = null,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(openApp()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = name,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                text = if (note == null) detail else "$detail · $note",
                style = TextStyle(
                    color = accent?.let { channelColor(it) } ?: GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
        if (pending) {
            CircularProgressIndicator(
                modifier = GlanceModifier.size(16.dp),
                color = GlanceTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * A widget with nothing to show says why, in one line, and stays tappable — every blocker here
 * has something the owner can do about it, and the whole surface is the button.
 */
@Composable
fun BlockerBody(blocker: WidgetBlocker, onRetry: Action) {
    val copy = blockerCopy(blocker)
    // Each blocker's tap goes where its fix lives: credentials to Settings, a missing or
    // unchosen device to the picker, and anything transient to a plain retry.
    val action = when (blocker) {
        WidgetBlocker.SIGNED_OUT, WidgetBlocker.UNAUTHORIZED -> openSettings()
        WidgetBlocker.NOT_CONFIGURED, WidgetBlocker.ENTITY_MISSING -> openConfig()
        else -> onRetry
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(action),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = copy.headline,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
        Text(
            text = copy.detail,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
        )
    }
}

/**
 * The widgets' one button shape. [action] of null renders it disabled rather than hiding it, so
 * the control never moves under the owner's thumb as states change.
 */
@Composable
fun WidgetButton(
    label: String,
    action: Action?,
    modifier: GlanceModifier = GlanceModifier,
    accent: Channel? = null,
    filled: Boolean = false,
) {
    val background: ColorProvider = when {
        action == null -> panelHigh
        filled && accent != null -> channelColor(accent)
        else -> panelHigh
    }
    val content: ColorProvider = when {
        action == null -> GlanceTheme.colors.onSurfaceVariant
        filled && accent != null -> onChannel(accent)
        accent != null -> channelColor(accent)
        else -> GlanceTheme.colors.onSurface
    }
    Column(
        modifier = modifier
            .height(TOUCH_TARGET)
            .background(background)
            .cornerRadius(14.dp)
            .let { if (action != null) it.clickable(action) else it },
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = content,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The clock time a reading was taken, in the user's own 12/24-hour setting.
 *
 * Lock and alarm widgets print this next to the state, always. A widget's frame outlives the
 * reading behind it — nothing redraws the home screen because a value aged out — so the read time
 * is what keeps a persisted "Locked" from becoming a lie. It is cheaper and more honest than
 * scheduling redraws forever for a widget that is only reachable on the tailnet anyway.
 */
@Composable
fun readAtLabel(readAtMs: Long?): String? {
    if (readAtMs == null) return null
    return DateFormat.getTimeFormat(LocalContext.current).format(Date(readAtMs))
}

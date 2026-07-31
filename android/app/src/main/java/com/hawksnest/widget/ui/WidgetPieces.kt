package com.hawksnest.widget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawksnest.MainActivity
import com.hawksnest.core.logic.Channel
import com.hawksnest.core.logic.CompactName
import com.hawksnest.core.logic.WidgetBlocker
import com.hawksnest.core.logic.blockerCopy
import com.hawksnest.R
import com.hawksnest.ui.glance.channelColor
import com.hawksnest.ui.glance.onEnergy
import com.hawksnest.ui.glance.onGradient
import com.hawksnest.ui.navigation.Screen
import com.hawksnest.widget.WidgetConfigActivity
import java.util.Date

/**
 * The pieces every Hawksnest widget is built from, so the three of them read as one family and as
 * a continuation of the in-app panels: a hairline-stroked surface, a glyph chip beside a name over
 * a state line, controls that carry their own glyphs, and one honest sentence when there is
 * nothing to control.
 */

/**
 * The full tier's control height. Above the 48dp touch floor on purpose: the control row is the
 * widget's one interactive register, and a little extra height is what keeps a glyph and a label
 * comfortable inside it. The compact tier ignores this and fills whatever height is left instead.
 */
private val CONTROL_HEIGHT = 52.dp

/** Opens the app. Every widget offers this on its title, so a tap always leads somewhere. */
fun openApp(): Action = actionStartActivity<MainActivity>()

/**
 * Opens the app on one entity's detail screen — the live card plus its state-history chart with
 * the 6h/24h/7d/30d toggle.
 *
 * This is what a *read-only* widget's tap should do. The control widgets have somewhere obvious to
 * go and something to do on the widget itself; a temperature widget's only follow-up question is
 * "and what has it been doing?", which is precisely the screen that already answers it. Landing on
 * Home instead made the reading a dead end.
 */
@Composable
fun openEntity(entityId: String): Action = actionStartActivity(
    Intent(LocalContext.current, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN_ENTITY, entityId)
        // CLEAR_TOP matters here. MainActivity is `standard` launch mode, so when the app is
        // already running, a NEW_TASK intent just brings the existing task forward and the extra
        // is never delivered — the tap would appear to do nothing. CLEAR_TOP re-delivers it
        // through onCreate, so the widget behaves the same whether the app was open or not.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
)

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

/**
 * The widget's surface: a PULSE panel lit from above, held by a hairline — and by a channel-lit
 * rim when [accent] says the thing inside has something to report. That rim is the Remnant idea,
 * scaled to a widget: light the edge instead of shadowing the box, so state is legible from across
 * the room before you read a word of it.
 *
 * Drawn from a shape drawable rather than a flat `ColorProvider` because gradients and strokes are
 * not expressible in Glance any other way. The drawable owns the corner radius, which is also why
 * it works below API 31, where `GlanceModifier.cornerRadius` is ignored.
 */
@Composable
fun WidgetPanel(
    compact: Boolean = false,
    accent: Channel? = null,
    /** Light the rim in the error red instead of a channel. PULSE has no red
     *  channel, and the one state that means "deal with this now" rather than
     *  "here is a reading" should not have to borrow orange. Wins over [accent]. */
    alert: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val panel = when {
        alert -> R.drawable.widget_panel_alert
        accent == Channel.EFFORT -> R.drawable.widget_panel_effort
        accent == Channel.STREAK -> R.drawable.widget_panel_streak
        accent == Channel.RECOVERY -> R.drawable.widget_panel_recovery
        else -> R.drawable.widget_panel
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(panel))
            // A one-row widget is ~70dp tall; 12dp of padding top and bottom would eat a third of it.
            .padding(if (compact) 6.dp else 12.dp),
        content = content,
    )
}

/** The soft-washed chip behind the header glyph — the widget's identity mark wearing its state. */
private fun chipFace(accent: Channel?): Int = when (accent) {
    Channel.EFFORT -> R.drawable.widget_chip_effort
    Channel.STREAK -> R.drawable.widget_chip_streak
    Channel.RECOVERY -> R.drawable.widget_chip_recovery
    else -> R.drawable.widget_chip
}

/**
 * Glyph chip, then name over state. The glyph and state line wear the channel color when there is
 * one — green for a locked door, blue for an armed panel — which is the same signal the in-app
 * cards give, now legible before a word is read. Compact drops the chip and inlines a small glyph
 * so the single line keeps its height.
 */
@Composable
fun WidgetHeader(
    name: String,
    detail: String,
    /** The widget's identity glyph — bulb, bolt, shield — tinted to the accent. */
    icon: Int,
    accent: Channel? = null,
    pending: Boolean = false,
    /**
     * Appended after the state, and always a caveat on it: how old a light's reading is, when a
     * lock was last read, or what went wrong. Never decoration.
     */
    note: String? = null,
    compact: Boolean = false,
    /** Where the compact tier puts the name, if anywhere — see `compactNamePlacement`. */
    namePlacement: CompactName = CompactName.INLINE,
    /**
     * Where a tap on the title goes. Defaults to the app, which is right for the control widgets —
     * their interesting action is the button below. The read-only temperature widget overrides it
     * to open that sensor's history chart, the only follow-up question its reading raises.
     */
    onClick: Action = openApp(),
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(onClick),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        val accentColor = accent?.let { channelColor(it) } ?: GlanceTheme.colors.onSurfaceVariant
        if (compact) {
            // No chip out here — just the small inline glyph, and then whatever the room allows.
            Image(
                provider = ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(accentColor),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            val status = listOfNotNull(detail, note).joinToString(" · ")
            val nameStyle = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (namePlacement == CompactName.STACKED) {
                // Too narrow to share a line, tall enough not to have to. The control below simply
                // gets that much less height — on a widget this shape it has height to spare, and
                // a lock that can't say which door it is has no business being on a home screen.
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(text = name, style = nameStyle, maxLines = 1)
                    Text(
                        text = status,
                        style = TextStyle(color = accentColor, fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
            } else {
                // One line, and the order of precedence is the point. The state and the read time
                // are laid out at their natural width so nothing can shorten them; the name takes
                // the leftover and ellipsizes into it. A long name therefore eats its own tail
                // rather than the state — the part a lock can least afford to lose.
                val inline = namePlacement == CompactName.INLINE
                if (inline) {
                    Text(
                        text = name,
                        modifier = GlanceModifier.defaultWeight(),
                        style = nameStyle,
                        maxLines = 1,
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                }
                Text(
                    text = status,
                    // Weighted only when it is the whole line; beside a name it keeps its own width.
                    modifier = if (inline) GlanceModifier else GlanceModifier.defaultWeight(),
                    style = TextStyle(color = accentColor, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        } else {
            Box(
                modifier = GlanceModifier.size(32.dp).background(ImageProvider(chipFace(accent))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(accentColor),
                )
            }
            Spacer(modifier = GlanceModifier.width(10.dp))
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
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
        if (pending) {
            CircularProgressIndicator(
                modifier = GlanceModifier.size(if (compact) 12.dp else 16.dp),
                color = GlanceTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * A widget with nothing to show says why, in one line under its own muted glyph, and stays
 * tappable — every blocker here has something the owner can do about it, and the whole surface is
 * the button.
 */
@Composable
fun BlockerBody(blocker: WidgetBlocker, onRetry: Action, icon: Int) {
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
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
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
 * The widgets' one button shape, in three registers: a plain face for an idle or disabled
 * control, a soft channel wash ([tinted]) for one that is live but resting, and the full PULSE
 * gradient ([filled]) for the engaged state. [action] of null renders it disabled rather than
 * hiding it, so the control never moves under the owner's thumb as states change.
 */
@Composable
fun WidgetButton(
    label: String,
    action: Action?,
    modifier: GlanceModifier = GlanceModifier,
    accent: Channel? = null,
    filled: Boolean = false,
    /** Soft channel wash for a live-but-resting control. Loses to [filled]; needs an [accent]. */
    tinted: Boolean = false,
    /** Drawn beside the label — or alone, with [iconDescription] speaking for it. */
    icon: Int? = null,
    /** Read out for an icon-only button; a labelled button's text already speaks. */
    iconDescription: String? = null,
    /** Glyph above the label instead of beside it — the full tier's segment look. */
    stacked: Boolean = false,
    /**
     * Take whatever height is left instead of the fixed [CONTROL_HEIGHT] row. Only for the
     * compact tier, where the widget itself is around one launcher row — there the button *is*
     * the widget, so the target is as big as the owner chose to make it.
     */
    fillHeight: Boolean = false,
) {
    // An engaged control wears its channel's PULSE gradient; a resting one its soft wash or the
    // plain panel face. [filled] outranks a null [action] deliberately: the engaged state is
    // often the one whose tap is a no-op — the alarm segment the panel is already in — and it
    // must still read as engaged, not as disabled.
    val face = when {
        filled && accent == Channel.STREAK -> R.drawable.widget_button_energy
        filled && accent == Channel.EFFORT -> R.drawable.widget_button_hero
        filled && accent == Channel.RECOVERY -> R.drawable.widget_button_recovery
        action == null -> R.drawable.widget_button_face
        tinted && accent == Channel.STREAK -> R.drawable.widget_button_soft_streak
        tinted && accent == Channel.RECOVERY -> R.drawable.widget_button_soft_recovery
        else -> R.drawable.widget_button_face
    }
    val content: ColorProvider = when {
        // Content sitting on a gradient can't use the channel's flat `on` colour — that is tuned
        // for the base hue and fails against the far stop. The energy sweep stays light in both
        // themes so it takes PULSE's warm ink; the other two run dark enough for white.
        filled && accent == Channel.STREAK -> onEnergy
        filled && accent != null -> onGradient
        action == null -> GlanceTheme.colors.onSurfaceVariant
        accent != null -> channelColor(accent)
        else -> GlanceTheme.colors.onSurface
    }
    val base = modifier
        .let { if (fillHeight) it.fillMaxHeight() else it.height(CONTROL_HEIGHT) }
        .background(ImageProvider(face))
        .let { if (action != null) it.clickable(action) else it }
    val labelStyle = TextStyle(
        color = content,
        fontSize = if (stacked) 12.sp else 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )

    @Composable
    fun glyph(sizeDp: Int) {
        Image(
            provider = ImageProvider(icon!!),
            contentDescription = iconDescription,
            modifier = GlanceModifier.size(sizeDp.dp),
            colorFilter = ColorFilter.tint(content),
        )
    }

    if (stacked && icon != null && label.isNotEmpty()) {
        Column(
            modifier = base,
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            glyph(20)
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(text = label, style = labelStyle, maxLines = 1)
        }
    } else {
        Row(
            modifier = base,
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            if (icon != null) {
                glyph(18)
                if (label.isNotEmpty()) Spacer(modifier = GlanceModifier.width(6.dp))
            }
            if (label.isNotEmpty()) Text(text = label, style = labelStyle, maxLines = 1)
        }
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

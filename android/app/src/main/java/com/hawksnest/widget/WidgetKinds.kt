package com.hawksnest.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.hawksnest.core.logic.WidgetKind

/**
 * The one place that knows which `GlanceAppWidget` a [WidgetKind] means. Both the repository
 * (which redraws a widget after a change) and the live bridge (which enumerates the widgets on the
 * home screen) need this mapping; adding a widget should mean editing one `when`.
 */
internal fun glanceWidget(kind: WidgetKind): GlanceAppWidget = when (kind) {
    WidgetKind.LIGHT -> LightWidget()
    WidgetKind.LOCK -> LockWidget()
    WidgetKind.ALARM -> AlarmWidget()
    WidgetKind.TEMPERATURE -> TemperatureWidget()
    WidgetKind.SWITCH -> SwitchWidget()
    WidgetKind.SCENE_PAD -> ScenePadWidget()
    WidgetKind.GARAGE -> GarageWidget()
}

internal fun glanceWidgetClass(kind: WidgetKind): Class<out GlanceAppWidget> = when (kind) {
    WidgetKind.LIGHT -> LightWidget::class.java
    WidgetKind.LOCK -> LockWidget::class.java
    WidgetKind.ALARM -> AlarmWidget::class.java
    WidgetKind.TEMPERATURE -> TemperatureWidget::class.java
    WidgetKind.SWITCH -> SwitchWidget::class.java
    WidgetKind.SCENE_PAD -> ScenePadWidget::class.java
    WidgetKind.GARAGE -> GarageWidget::class.java
}

/** The manifest receiver behind each kind — what the platform counts placed widgets by. */
internal fun glanceReceiverClass(kind: WidgetKind): Class<out GlanceAppWidgetReceiver> = when (kind) {
    WidgetKind.LIGHT -> LightWidgetReceiver::class.java
    WidgetKind.LOCK -> LockWidgetReceiver::class.java
    WidgetKind.ALARM -> AlarmWidgetReceiver::class.java
    WidgetKind.TEMPERATURE -> TemperatureWidgetReceiver::class.java
    WidgetKind.SWITCH -> SwitchWidgetReceiver::class.java
    WidgetKind.SCENE_PAD -> ScenePadWidgetReceiver::class.java
    WidgetKind.GARAGE -> GarageWidgetReceiver::class.java
}

/**
 * What every widget receiver shares: keeping the periodic refresh job in step with whether any
 * widget exists. `onEnabled` is a provider's first instance and `onDisabled` its last, which is
 * per *kind* — so neither decides anything itself; both just ask the scheduler to recount.
 */
abstract class HawksnestWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshScheduler.sync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshScheduler.sync(context)
    }
}

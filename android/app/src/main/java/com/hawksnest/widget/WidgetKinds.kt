package com.hawksnest.widget

import androidx.glance.appwidget.GlanceAppWidget
import com.hawksnest.core.logic.WidgetKind

/**
 * The one place that knows which `GlanceAppWidget` a [WidgetKind] means. Both the repository
 * (which redraws a widget after a change) and the live bridge (which enumerates the widgets on the
 * home screen) need this mapping; adding a fourth widget should mean editing one `when`.
 */
internal fun glanceWidget(kind: WidgetKind): GlanceAppWidget = when (kind) {
    WidgetKind.LIGHT -> LightWidget()
    WidgetKind.LOCK -> LockWidget()
    WidgetKind.ALARM -> AlarmWidget()
}

internal fun glanceWidgetClass(kind: WidgetKind): Class<out GlanceAppWidget> = when (kind) {
    WidgetKind.LIGHT -> LightWidget::class.java
    WidgetKind.LOCK -> LockWidget::class.java
    WidgetKind.ALARM -> AlarmWidget::class.java
}

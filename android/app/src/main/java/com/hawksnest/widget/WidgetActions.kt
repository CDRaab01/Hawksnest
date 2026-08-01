package com.hawksnest.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.widget.data.ActionTarget
import com.hawksnest.widget.data.WidgetEntryPoint

/**
 * The three things a tap on a widget can mean. Each is parameterised by widget kind and service
 * rather than duplicated per domain, so a light's toggle and an alarm's disarm travel the same
 * path and get the same treatment.
 *
 * All three hand off immediately. An `ActionCallback` runs inside a broadcast's roughly ten
 * seconds of guaranteed execution, and a lock's echo wait can take thirty — so the callback only
 * starts the work, and `WidgetRepository` finishes it in the application scope.
 */
object WidgetParams {
    val KIND = ActionParameters.Key<String>("kind")
    val SERVICE = ActionParameters.Key<String>("service")
    val BRIGHTNESS_PCT = ActionParameters.Key<Int>("brightness_pct")

    /** A select's chosen option — the scene pad's keys. */
    val OPTION = ActionParameters.Key<String>("option")

    /** [ActionTarget] by name, absent meaning the widget's primary entity. */
    val TARGET = ActionParameters.Key<String>("target")
}

fun widgetParams(
    kind: WidgetKind,
    service: String? = null,
    brightnessPct: Int? = null,
    option: String? = null,
    target: ActionTarget = ActionTarget.PRIMARY,
): ActionParameters =
    actionParametersOf(
        *listOfNotNull(
            WidgetParams.KIND to kind.name,
            service?.let { WidgetParams.SERVICE to it },
            brightnessPct?.let { WidgetParams.BRIGHTNESS_PCT to it },
            option?.let { WidgetParams.OPTION to it },
            (WidgetParams.TARGET to target.name).takeIf { target != ActionTarget.PRIMARY },
        ).toTypedArray()
    )

private fun ActionParameters.kind(): WidgetKind? =
    this[WidgetParams.KIND]?.let { name -> WidgetKind.entries.firstOrNull { it.name == name } }

/** Send a service call and reconcile — the ordinary tap. */
class WidgetServiceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters.kind() ?: return
        val service = parameters[WidgetParams.SERVICE] ?: return
        val extra = buildMap<String, Any?> {
            parameters[WidgetParams.BRIGHTNESS_PCT]?.let { put("brightness_pct", it) }
            parameters[WidgetParams.OPTION]?.let { put("option", it) }
        }
        val target = parameters[WidgetParams.TARGET]
            ?.let { name -> ActionTarget.entries.firstOrNull { it.name == name } }
            ?: ActionTarget.PRIMARY
        WidgetEntryPoint.get(context).repository().actAsync(kind, glanceId, service, extra, target)
    }
}

/** Arm a "tap again" for a destructive command. Sends nothing to Home Assistant. */
class WidgetConfirmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters.kind() ?: return
        val service = parameters[WidgetParams.SERVICE] ?: return
        WidgetEntryPoint.get(context).repository().armConfirmAsync(kind, glanceId, service)
    }
}

/** Re-read the entity — the retry offered on every error state. */
class WidgetRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters.kind() ?: return
        WidgetEntryPoint.get(context).repository().refreshAsync(kind, glanceId)
    }
}

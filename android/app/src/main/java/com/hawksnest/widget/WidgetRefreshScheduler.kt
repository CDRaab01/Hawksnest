package com.hawksnest.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hawksnest.core.logic.WidgetKind
import com.hawksnest.core.logic.WidgetScheduleAction
import com.hawksnest.core.logic.widgetScheduleAction
import java.util.concurrent.TimeUnit

/**
 * Owns the single periodic job that keeps widgets current while the app is closed.
 *
 * Every provider used to declare `updatePeriodMillis="1800000"`. That is seven separate platform
 * alarms, and each tick broadcast to one provider, which started a Glance session per widget,
 * whose render kicked a REST read, whose result was written back and drawn. With nine widgets
 * placed that measured as the #2 partial wakelock on the owner's phone: 124 Glance
 * `SessionWorker` jobs and 43 minutes of job runtime in under six hours, nearly all of it to
 * redraw a lamp as the same lamp.
 *
 * The periodic read still matters — since the HA socket began closing 30s after the app leaves
 * the screen, `WidgetLiveBridge` is silent in the background and this is the only thing keeping a
 * temperature or a lock current between glances. So it is kept, as ONE job:
 *  - one wake for every widget rather than one alarm per provider, batched by JobScheduler with
 *    whatever else the phone was waking for, and deferred in Doze;
 *  - only with a network (`CONNECTED`) — without one the old tick still spun up nine sessions to
 *    fail nine fetches;
 *  - one read per distinct entity, and a Glance update only where the picture changed
 *    (`widgetRefreshNeedsRedraw`).
 *
 * The interval stays at 30 minutes: the same freshness the widgets had, so nothing on the home
 * screen gets staler for this. The saving is in what a tick costs, not in how often it comes.
 */
object WidgetRefreshScheduler {

    /**
     * Assert the schedule against the widgets actually on the home screen. Safe to call from
     * anywhere, any number of times — it is called on every process start, on a kind's first
     * widget placed and on its last removed.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val action = widgetScheduleAction(
            placedWidgets = placedWidgets(app),
            storedSignature = store.getString(KEY_SIGNATURE, null),
            signature = SIGNATURE,
        )
        val work = WorkManager.getInstance(app)
        when (action) {
            WidgetScheduleAction.CANCEL -> {
                work.cancelUniqueWork(WORK_NAME)
                // Forget the signature with the job, so the next widget placed enqueues from
                // scratch rather than trusting a record of work that no longer exists.
                store.edit().remove(KEY_SIGNATURE).apply()
            }
            WidgetScheduleAction.KEEP ->
                work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
            WidgetScheduleAction.REPLACE -> {
                work.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    request(),
                )
                store.edit().putString(KEY_SIGNATURE, SIGNATURE).apply()
            }
        }
    }

    private fun request() =
        PeriodicWorkRequestBuilder<WidgetRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

    /** Asked of the platform rather than of Glance: synchronous, so callable from a receiver. */
    private fun placedWidgets(context: Context): Int {
        val manager = AppWidgetManager.getInstance(context) ?: return 0
        return WidgetKind.entries.sumOf { kind ->
            manager.getAppWidgetIds(ComponentName(context, glanceReceiverClass(kind))).size
        }
    }

    private const val WORK_NAME = "widget-refresh"
    private const val PREFS = "widget_refresh"
    private const val KEY_SIGNATURE = "schedule_signature"
    private const val INTERVAL_MINUTES = 30L

    /**
     * Everything the schedule is made of. CHANGE THIS WHENEVER [request] CHANGES — it is what
     * turns the everyday KEEP into a one-time replace; see `widgetScheduleAction`.
     */
    private const val SIGNATURE = "v1|${INTERVAL_MINUTES}m|CONNECTED"
}

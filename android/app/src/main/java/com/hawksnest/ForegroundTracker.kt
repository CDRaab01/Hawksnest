package com.hawksnest

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Reports whether ANY activity is started — the app's "is something on screen" signal.
 *
 * A started-activity count rather than `ProcessLifecycleOwner`: it is the same mechanism without
 * a new dependency, and it has no built-in debounce to reason about on top of the grace period
 * its one consumer ([com.hawksnest.core.ha.ConnectionManager.setForeground]) already applies. The
 * count briefly touching zero (a rotation, MainActivity → WidgetConfigActivity) is expected and
 * is exactly what that grace period absorbs — so this reports every edge, honestly.
 *
 * Picture-in-picture counts as foreground, correctly: a PiP activity is paused, not stopped, and
 * its live camera may be negotiating over the very socket this gates.
 */
class ForegroundTracker(
    private val onChanged: (foreground: Boolean) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private var started = 0

    // The counting is split from the callbacks so it can be unit-tested without an Activity.
    internal fun activityStarted() {
        started += 1
        if (started == 1) onChanged(true)
    }

    internal fun activityStopped() {
        // Never below zero: a callback registered mid-flight could see a stop with no start.
        if (started == 0) return
        started -= 1
        if (started == 0) onChanged(false)
    }

    override fun onActivityStarted(activity: Activity) = activityStarted()
    override fun onActivityStopped(activity: Activity) = activityStopped()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

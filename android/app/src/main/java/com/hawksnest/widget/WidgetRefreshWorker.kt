package com.hawksnest.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hawksnest.widget.data.WidgetEntryPoint
import kotlinx.coroutines.CancellationException

/**
 * The one periodic wake the widgets are allowed: read every placed widget's entity in a single
 * pass, and redraw only the ones whose picture is now wrong.
 *
 * It replaces `updatePeriodMillis`, which did the same job seven providers at a time — see
 * [WidgetRefreshScheduler] for why that was expensive.
 *
 * **This worker never retries.** Every outcome is `success()`, including a failed fetch and an
 * exception. WorkManager's retry is an exponential backoff that survives force-stop (it lives in
 * WorkManager's own database), and a periodic job that asks for one can end up further and
 * further behind its own schedule. The next period *is* the retry; a widget that missed a read
 * says so on its face and refetches the moment it is next rendered or tapped.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val placed = WidgetEntryPoint.get(applicationContext).repository().refreshPlaced()
            // The last widget went away without `onDisabled` reaching us (a cleared launcher, a
            // restore). Stop waking up for an empty home screen.
            if (placed == 0) WidgetRefreshScheduler.sync(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logged and swallowed on purpose — see the class comment.
            Log.w(TAG, "Widget background refresh failed; waiting for the next period", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "WidgetRefreshWorker"
    }
}

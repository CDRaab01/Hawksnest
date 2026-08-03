package com.hawksnest.crash

import android.os.Build
import com.hawksnest.BuildConfig
import com.hawksnest.core.logic.CrashReport
import com.hawksnest.core.logic.formatCrashReport
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures uncaught exceptions to disk so a crash on the phone leaves a trace.
 *
 * Before this, a crash left nothing at all: no logcat unless someone had a cable attached at the
 * moment it happened, and nothing in the app afterwards. The audit filed that as the reason every
 * other Android item ships blind.
 *
 * Three rules this follows, each of which is easy to get wrong:
 *
 * 1. **Write to disk here; send later.** The process is being torn down — a network call has no
 *    reliable chance to complete, and attempting one risks hanging the death of the process or
 *    losing the report entirely. [CrashUploader] publishes on the next launch instead. The cost is
 *    that a crash which permanently prevents startup is never *sent*, but it is still on disk and
 *    visible over adb.
 *
 * 2. **Always chain to the previous handler.** Swallowing an uncaught exception leaves the process
 *    alive in an undefined state, which is strictly worse than crashing: the user gets a frozen
 *    app instead of a restart, and the "Hawksnest keeps stopping" dialog never appears.
 *
 * 3. **Never throw from in here.** A handler that throws replaces a diagnosable crash with an
 *    undiagnosable one, so every step is wrapped.
 *
 * Deliberately NOT a Sentry/GlitchTip client. Self-hosting GlitchTip was the original plan
 * (ROADMAP2 T1 #7) but it wants ~1.5–2 GB for web + worker + Postgres + Redis, and this is a
 * one-person fleet on a host that also runs the door locks and a seven-camera NVR. The two things
 * a crash service really buys — aggregation and symbolication — are worth less here than usual:
 * `isMinifyEnabled = false`, so these traces are already readable. If that ever changes, this
 * class is the seam to swap.
 */
@Singleton
class CrashReporter @Inject constructor(
    private val store: CrashStore,
) {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { capture(thread, error) }
            // Rule 2: let the platform do what it was going to do.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun capture(thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val stack = StringWriter().also { sw -> error.printStackTrace(PrintWriter(sw)) }.toString()
        val report = CrashReport(
            whenMs = now,
            threadName = thread.name,
            type = error.javaClass.name,
            message = error.message.orEmpty(),
            stack = stack,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidRelease = Build.VERSION.RELEASE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        // formatCrashReport scrubs credentials — nothing unscrubbed is ever written.
        store.write(now, formatCrashReport(report, isoUtc(now)))
    }

    companion object {
        fun isoUtc(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
    }
}

package com.hawksnest.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hawksnest.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the notification channels and turns an [NtfyMessage] into a posted
 * notification (or the persistent foreground notification the service runs
 * under). Channel + importance + tap destination are derived from [PushRoute],
 * so a doorbell buzzes loudly and deep-links to its camera, an alarm change
 * opens Home. A doorbell that carries a snapshot renders it as a big picture.
 */
@Singleton
class PushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    /** Create the channels once (idempotent). Called from the Application. */
    fun createChannels() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_DOORBELL, "Doorbell", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Someone pressed a doorbell." },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Alarm", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Security alarm state changes." },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_GENERIC, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Other Home Assistant alerts." },
        )
        mgr.createNotificationChannel(
            // Low importance: silent, no heads-up — it's just the "listening" chip.
            NotificationChannel(CHANNEL_SERVICE, "Push service", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps Hawksnest listening for alerts." },
        )
    }

    /** The persistent notification the foreground service runs under. */
    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Hawksnest")
            .setContentText("Listening for alerts")
            .setSmallIcon(context.applicationInfo.icon)
            .setOngoing(true)
            .setContentIntent(contentIntent(null))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Post a notification for an incoming message. No-op if the user revoked POST_NOTIFICATIONS. */
    fun show(msg: NtfyMessage) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val kind = PushRoute.kindOf(msg)
        val channel = when (kind) {
            PushKind.Doorbell -> CHANNEL_DOORBELL
            PushKind.Alarm -> CHANNEL_ALARM
            PushKind.Generic -> CHANNEL_GENERIC
        }
        // Doorbell snapshot: fetch best-effort (the camera_proxy URL is self-authing via its
        // signed token). Runs on the service's IO coroutine, so a blocking fetch is fine.
        val snapshot = msg.attachUrl?.let { fetchBitmap(it) }
        val builder = NotificationCompat.Builder(context, channel)
            .setContentTitle(msg.title)
            .setContentText(msg.body)
            .setSmallIcon(context.applicationInfo.icon)
            .setAutoCancel(true)
            .setCategory(if (kind == PushKind.Alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (msg.priority >= 4) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(PushRoute.cameraOf(msg)))
        if (snapshot != null) {
            builder.setLargeIcon(snapshot)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(snapshot)
                        .bigLargeIcon(null as Bitmap?), // hide the thumbnail when expanded
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
        }
        val notification = builder.build()
        try {
            // Stable-ish id per message so repeats replace rather than stack endlessly.
            nm.notify(msg.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and here — ignore.
        }
    }

    /** Best-effort image fetch for the notification snapshot; null on any failure. */
    private fun fetchBitmap(url: String): Bitmap? = try {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The tap intent. Always brings the app to Home (`FLAG_ACTIVITY_SINGLE_TOP`, so a
     * running app gets `onNewIntent` rather than a fresh task); a doorbell additionally
     * carries the camera id so Home opens its live view. Distinct request code per
     * camera so a doorbell PendingIntent doesn't overwrite an alarm one.
     */
    private fun contentIntent(cameraId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (cameraId != null) intent.putExtra(EXTRA_CAMERA, cameraId)
        return PendingIntent.getActivity(
            context,
            (cameraId ?: "home").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_DOORBELL = "doorbell"
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_GENERIC = "alerts"
        const val CHANNEL_SERVICE = "push_service"

        /** Intent extra carrying the logical camera id a doorbell tap should open. */
        const val EXTRA_CAMERA = "com.hawksnest.push.EXTRA_CAMERA"
    }
}

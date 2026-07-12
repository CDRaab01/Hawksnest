package com.hawksnest.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hawksnest.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the notification channels and turns an [NtfyMessage] into a posted
 * notification (or the persistent foreground notification the service runs
 * under). Channel + importance + tap destination are derived from [PushRoute],
 * so a doorbell buzzes loudly and opens the cameras, an alarm change opens Home.
 */
@Singleton
class PushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
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
            .setContentIntent(contentIntent(PushRoute.ROUTE_HOME))
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
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(msg.title)
            .setContentText(msg.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
            .setSmallIcon(context.applicationInfo.icon)
            .setAutoCancel(true)
            .setCategory(if (kind == PushKind.Alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (msg.priority >= 4) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(PushRoute.routeFor(kind)))
            .build()
        try {
            // Stable-ish id per message so repeats replace rather than stack endlessly.
            nm.notify(msg.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and here — ignore.
        }
    }

    private fun contentIntent(route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ROUTE, route)
        return PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_DOORBELL = "doorbell"
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_GENERIC = "alerts"
        const val CHANNEL_SERVICE = "push_service"

        /** Intent extra carrying the nav route a tapped notification should open. */
        const val EXTRA_ROUTE = "com.hawksnest.push.EXTRA_ROUTE"
    }
}

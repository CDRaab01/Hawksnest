package com.hawksnest.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hawksnest.MainActivity
import com.hawksnest.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives FCM security pushes and posts a channel-routed, deep-linked notification. The HA-side
 * sender stamps `severity`/`title`/`body`/`entity_id` on the data payload; life-safety routes to its
 * own always-alerting channel. (Biometric-gated action buttons + quiet hours land in a follow-up.)
 */
@AndroidEntryPoint
class HawksnestMessagingService : FirebaseMessagingService() {

    @Inject lateinit var enrollment: FcmEnrollment

    override fun onNewToken(token: String) {
        enrollment.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val channel = PushChannels.channelFor(data["severity"])
        val title = data["title"] ?: message.notification?.title ?: "Hawksnest"
        val body = data["body"] ?: message.notification?.body ?: "Security event"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data["entity_id"]?.let { putExtra("entity_id", it) }
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            data["entity_id"]?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (channel == PushChannels.INFO) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (channel == PushChannels.INFO) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val id = data["entity_id"]?.hashCode() ?: message.messageId?.hashCode() ?: channel.hashCode()
        getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}

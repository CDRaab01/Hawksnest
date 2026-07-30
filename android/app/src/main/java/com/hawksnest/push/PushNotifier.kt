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
 * opens Home, and a camera object alert lands on its own mutable channel. Any
 * message carrying a snapshot renders it as a big picture.
 *
 * The image is fetched with **no auth headers** — every URL the automations send
 * must self-authenticate (HA's signed `camera_proxy` token). A URL needing a
 * bearer would silently fall back to a text-only notification.
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
            NotificationChannel(
                CHANNEL_PERSON,
                "Person detected",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "A camera saw a person while the alarm was armed." },
        )
        mgr.createNotificationChannel(
            // Default importance: it posts, but doesn't shove a heads-up in your face.
            NotificationChannel(CHANNEL_PET, "Pet detected", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A camera saw a dog or cat." },
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
            PushKind.Person -> CHANNEL_PERSON
            PushKind.Pet -> CHANNEL_PET
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
            // Person counts as CATEGORY_ALARM alongside the alarm panel: the HA
            // automation only sends it while armed, so it genuinely is a security
            // event, not a message.
            .setCategory(
                if (kind == PushKind.Alarm || kind == PushKind.Person) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_MESSAGE
                },
            )
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
            nm.notify(notificationId(msg, kind), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and here — ignore.
        }
    }

    /**
     * The notification id, which decides whether a new alert **replaces** an old one
     * or stacks beside it.
     *
     * Camera object alerts key on the CAMERA, not the message: Frigate tracks each
     * object separately, and one person crossing a room routinely produces several
     * concurrent tracked objects (measured on the live kitchen camera: three at
     * once). Keying on `msg.id` would post three near-identical notifications for
     * one person. Keying on the camera means the newest frame for that camera wins
     * and the phone shows one entry per camera — which is also why no server-side
     * cooldown was needed.
     *
     * Everything else keeps the per-message id: two doorbell presses ARE two events.
     */
    private fun notificationId(msg: NtfyMessage, kind: PushKind): Int =
        when (kind) {
            PushKind.Person, PushKind.Pet ->
                ("camobj:" + (PushRoute.cameraOf(msg) ?: "unknown")).hashCode()
            else -> msg.id.hashCode()
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

        // NEW ids rather than re-tuning `alerts`: Android ignores importance changes
        // to a channel it has already created, so editing the existing one would be
        // a silent no-op on every install that already ran.
        const val CHANNEL_PERSON = "camera_person"
        const val CHANNEL_PET = "camera_pet"

        const val CHANNEL_GENERIC = "alerts"
        const val CHANNEL_SERVICE = "push_service"

        /** Intent extra carrying the logical camera id a doorbell tap should open. */
        const val EXTRA_CAMERA = "com.hawksnest.push.EXTRA_CAMERA"
    }
}

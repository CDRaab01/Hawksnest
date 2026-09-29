package com.hawksnest.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hawksnest.MainActivity
import com.hawksnest.core.logic.CameraStart
import com.hawksnest.core.logic.QUICK_REPLIES
import com.hawksnest.core.logic.QuickReply
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the notification channels and turns an [NtfyMessage] into a posted
 * notification (or the persistent foreground notification the service runs
 * under). Channel + importance come from [PushRoute.kindOf]; where a tap lands and
 * which buttons it carries come from [PushRoute.tapTarget] and [PushRoute.actionsFor],
 * so a doorbell buzzes loudly and opens its camera, a garage alert opens the door,
 * and a camera object alert lands on its own mutable channel. Any message carrying
 * a snapshot renders it as a big picture.
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
            .setContentIntent(openIntent(PushTarget.Home(), msg = null, key = "service", dismissId = null))
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
        val id = notificationId(msg, kind)
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
            .setContentIntent(openIntent(PushRoute.tapTarget(msg), msg, key = "$id:tap", dismissId = id))
        PushRoute.actionsFor(msg).forEach { builder.addAction(compatAction(it, msg, id)) }
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
            nm.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and here — ignore.
        }
    }

    /**
     * The doorbell's "Reply": the same notification, its buttons swapped for the three quick
     * replies. In place and silent, so the snapshot of who is at the door stays on screen while
     * you pick one.
     */
    fun showReplyMenu(id: Int, cameraId: String) {
        val replies = QUICK_REPLIES.map { reply ->
            platformAction(
                buttonLabel(reply),
                broadcast(PushActionReceiver.ACTION_REPLY_PLAY, key = "$id:reply:${reply.id}") {
                    putExtra(EXTRA_NOTIFICATION_ID, id)
                    putExtra(EXTRA_CAMERA, cameraId)
                    putExtra(EXTRA_REPLY, reply.id)
                },
            )
        }
        update(id, text = null, actions = replies, fallbackChannel = CHANNEL_DOORBELL)
    }

    /** Progress or outcome of a quick reply, with the camera one button away either way. */
    fun showReplyStatus(id: Int, cameraId: String, text: String, done: Boolean) {
        val actions = if (!done) {
            emptyList()
        } else {
            listOf(
                openAction("Watch", PushTarget.Camera(cameraId), id),
                openAction("Talk", PushTarget.Camera(cameraId, start = CameraStart.TALK), id),
            )
        }
        update(id, text, actions, fallbackChannel = CHANNEL_DOORBELL)
    }

    /** Progress or outcome of "Arm away", as Home Assistant read it back. */
    fun showArmStatus(id: Int, entityId: String, text: String, done: Boolean) {
        val actions = if (done) listOf(openAction("View alarm", PushTarget.Entity(entityId), id)) else emptyList()
        update(id, text, actions, fallbackChannel = CHANNEL_ALARM)
    }

    /**
     * Rewrite a posted notification's text and buttons without buzzing again. Falls back to a fresh
     * notification when the original was swiped away mid-call: the outcome of something that
     * changed the house, or spoke at the door, is worth seeing either way.
     */
    private fun update(id: Int, text: String?, actions: List<Notification.Action>, fallbackChannel: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val active = mgr.activeNotifications.firstOrNull { it.id == id }?.notification
        val builder = if (active != null) {
            Notification.Builder.recoverBuilder(context, active)
        } else {
            Notification.Builder(context, fallbackChannel)
                .setContentTitle("Hawksnest")
                .setSmallIcon(context.applicationInfo.icon)
                .setAutoCancel(true)
        }
        if (text != null) {
            builder.setContentText(text)
            // A snapshot notification's expanded view shows the content text under the picture;
            // a text-only one shows its big text, which would still read the old message.
            if (active?.extras?.containsKey(Notification.EXTRA_PICTURE) != true) {
                builder.setStyle(Notification.BigTextStyle().bigText(text))
            }
        }
        builder.setActions(*actions.toTypedArray()).setOnlyAlertOnce(true)
        try {
            mgr.notify(id, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked — nothing to show it on.
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

    private fun compatAction(action: PushAction, msg: NtfyMessage, id: Int): NotificationCompat.Action {
        val intent = when (action) {
            is PushAction.Open -> openIntent(action.target, msg, key = "$id:${action.label}", dismissId = id)
            is PushAction.ReplyMenu -> broadcast(PushActionReceiver.ACTION_REPLY_MENU, key = "$id:reply") {
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_CAMERA, action.cameraId)
            }
            is PushAction.ArmAway -> broadcast(PushActionReceiver.ACTION_ARM_AWAY, key = "$id:arm") {
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_ENTITY, action.entityId)
            }
        }
        return NotificationCompat.Action.Builder(context.applicationInfo.icon, action.label, intent)
            // Arming changes the house, so it waits for the phone to be unlocked (Android 12+):
            // a phone left on a table must not be a way to arm away on someone who is home.
            .setAuthenticationRequired(action is PushAction.ArmAway)
            .build()
    }

    private fun openAction(label: String, target: PushTarget, id: Int): Notification.Action =
        platformAction(label, openIntent(target, msg = null, key = "$id:$label", dismissId = id))

    private fun platformAction(label: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, context.applicationInfo.icon), label, intent)
            .build()

    /**
     * An intent that opens the app on [target]. `SINGLE_TOP | CLEAR_TOP`, so a running app gets
     * `onNewIntent` rather than a second copy of itself; MainActivity reads the extras the same way
     * either way. [dismissId] clears the notification once it has been acted on, which a button,
     * unlike a tap on the notification itself, doesn't do on its own.
     *
     * [key] must be unique per notification and button. PendingIntents that differ only in their
     * extras are the same PendingIntent to Android, and FLAG_UPDATE_CURRENT would rewrite one
     * button's destination with another's.
     */
    private fun openIntent(target: PushTarget, msg: NtfyMessage?, key: String, dismissId: Int?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        when (target) {
            is PushTarget.Camera -> {
                intent.putExtra(EXTRA_CAMERA, target.cameraId)
                target.eventId?.let { intent.putExtra(EXTRA_EVENT, it) }
                if (target.start != CameraStart.LIVE) intent.putExtra(EXTRA_CAMERA_START, target.start.name)
            }
            is PushTarget.Entity -> intent.putExtra(MainActivity.EXTRA_OPEN_ENTITY, target.entityId)
            is PushTarget.Home -> if (target.banner && msg != null) {
                intent.putExtra(EXTRA_ALERT_TITLE, msg.title).putExtra(EXTRA_ALERT_BODY, msg.body)
            }
        }
        dismissId?.let { intent.putExtra(EXTRA_NOTIFICATION_ID, it) }
        return PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcast(action: String, key: String, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            Intent(context, PushActionReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Button-length versions of the quick replies: three full sentences don't fit on one row. */
    private fun buttonLabel(reply: QuickReply): String = when (reply.id) {
        "leave" -> "Leave at door"
        "coming" -> "Be right there"
        "cant" -> "Can't come now"
        else -> reply.label
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

        /** Intent extra carrying the logical camera id a tap or button should open. */
        const val EXTRA_CAMERA = "com.hawksnest.push.EXTRA_CAMERA"

        /** Intent extra carrying the Frigate event id a camera-alert tap should
         *  seek to, so you land on the moment rather than the live view. */
        const val EXTRA_EVENT = "com.hawksnest.push.EXTRA_EVENT"

        /** How the camera starts, a [CameraStart] name: the doorbell's "Talk" opens the mic. */
        const val EXTRA_CAMERA_START = "com.hawksnest.push.EXTRA_CAMERA_START"

        /** A triggered alarm's own words, pinned over Home as a banner. */
        const val EXTRA_ALERT_TITLE = "com.hawksnest.push.EXTRA_ALERT_TITLE"
        const val EXTRA_ALERT_BODY = "com.hawksnest.push.EXTRA_ALERT_BODY"

        /** The notification a button belongs to, so acting on it can update or clear it. */
        const val EXTRA_NOTIFICATION_ID = "com.hawksnest.push.EXTRA_NOTIFICATION_ID"

        /** The alarm panel "Arm away" arms. */
        const val EXTRA_ENTITY = "com.hawksnest.push.EXTRA_ENTITY"

        /** The [QuickReply.id] a reply button plays. */
        const val EXTRA_REPLY = "com.hawksnest.push.EXTRA_REPLY"
    }
}

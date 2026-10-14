package com.hawksnest.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hawksnest.core.logic.QUICK_REPLIES
import com.hawksnest.core.logic.quickReplyPath
import com.hawksnest.widget.data.CredentialSource
import com.hawksnest.widget.data.HaCall
import com.hawksnest.widget.data.WidgetHaClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The notification buttons that act without opening the app: the doorbell's "Reply" (and the three
 * replies it offers) and a disarmed alert's "Arm away". Buttons that open something are plain
 * activity intents and never come here.
 *
 * Speaks to Home Assistant the way the widgets do, over REST with the stored token
 * ([WidgetHaClient]): a button can be pressed from the lock screen with the app long gone, when
 * there is no socket to borrow. Every outcome is written back onto the notification, including
 * failure, so a button never looks like it worked when it didn't.
 *
 * Not exported: only this app's own PendingIntents can reach it.
 */
@AndroidEntryPoint
class PushActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: PushNotifier
    @Inject lateinit var haClient: WidgetHaClient
    @Inject lateinit var credentials: CredentialSource
    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PushNotifier.EXTRA_NOTIFICATION_ID, 0)
        when (intent.action) {
            ACTION_REPLY_MENU -> {
                val camera = intent.getStringExtra(PushNotifier.EXTRA_CAMERA) ?: return
                notifier.showReplyMenu(id, camera)
            }
            ACTION_REPLY_PLAY -> {
                val camera = intent.getStringExtra(PushNotifier.EXTRA_CAMERA) ?: return
                val reply = QUICK_REPLIES.firstOrNull { it.id == intent.getStringExtra(PushNotifier.EXTRA_REPLY) } ?: return
                notifier.showReplyStatus(id, camera, "Playing “${reply.label}”…", done = false)
                runAsync(
                    onTimeout = { notifier.showReplyStatus(id, camera, PushRoute.replyOutcome(reply.label, sent = false), done = true) },
                ) {
                    // `camera.<name>` → go2rtc's stream name, as the in-app player derives it.
                    val sent = playReply(quickReplyPath(camera.substringAfter('.', camera), reply))
                    notifier.showReplyStatus(id, camera, PushRoute.replyOutcome(reply.label, sent), done = true)
                }
            }
            ACTION_ARM_AWAY -> {
                val entity = intent.getStringExtra(PushNotifier.EXTRA_ENTITY) ?: return
                // The policy lives in PushRoute (arm only, never disarm); this is the second lock
                // on that door, so a crafted intent can't turn this receiver into something else.
                if (!entity.startsWith("alarm_control_panel.")) return
                notifier.showArmStatus(id, entity, "Arming away…", done = false)
                runAsync(
                    onTimeout = { notifier.showArmStatus(id, entity, PushRoute.armAwayOutcome(null), done = true) },
                ) {
                    notifier.showArmStatus(id, entity, armAway(entity), done = true)
                }
            }
        }
    }

    /**
     * Run [block] past onReceive's return, inside the window Android gives a receiver that called
     * [goAsync]. Capped under that window: an unfinished call must still end in an honest message,
     * not a process killed mid-sentence with "Arming away…" left on screen.
     */
    private fun runAsync(onTimeout: () -> Unit, block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (withTimeoutOrNull(WORK_BUDGET_MS) { block() } == null) onTimeout()
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Push a quick reply out of the camera's speaker: the same go2rtc call the in-app reply sheet
     * makes (`CameraPlayerViewModel.sendQuickReply`), from the same origin the app connects to.
     */
    private suspend fun playReply(path: String): Boolean {
        val base = credentials.current()?.baseUrl?.trimEnd('/') ?: return false
        return runCatching {
            okHttpClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url("$base/$path").post(ByteArray(0).toRequestBody()).build())
                .execute()
                .use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Arm, then read the panel back until it says it took, and report whatever it last said. */
    private suspend fun armAway(entityId: String): String {
        if (haClient.callService("alarm_control_panel", "alarm_arm_away", entityId) is HaCall.Failed) {
            return "Couldn't reach Home Assistant, so the alarm wasn't armed. Open Hawksnest to arm it."
        }
        var last: String? = null
        repeat(READBACK_TRIES) {
            delay(READBACK_INTERVAL_MS)
            (haClient.state(entityId) as? HaCall.Ok)?.value?.state?.let { last = it }
            if (PushRoute.armAwayTook(last)) return PushRoute.armAwayOutcome(last)
        }
        return PushRoute.armAwayOutcome(last)
    }

    companion object {
        const val ACTION_REPLY_MENU = "com.hawksnest.push.action.REPLY_MENU"
        const val ACTION_REPLY_PLAY = "com.hawksnest.push.action.REPLY_PLAY"
        const val ACTION_ARM_AWAY = "com.hawksnest.push.action.ARM_AWAY"

        /** Under the ~10 s Android allows a receiver that went async. */
        private const val WORK_BUDGET_MS = 8_500L
        private const val READBACK_TRIES = 5
        private const val READBACK_INTERVAL_MS = 1_000L
    }
}

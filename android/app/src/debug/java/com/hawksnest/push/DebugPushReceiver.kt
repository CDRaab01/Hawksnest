package com.hawksnest.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug builds only: post a test alert exactly as if ntfy had delivered it.
 *
 * ```
 * adb shell am broadcast -a com.hawksnest.debug.PUSH -p com.hawksnest \
 *   --es title "Doorbell" --es body "Someone is at the front door" --es tags bell \
 *   --es click "https://example/?camera=camera.front_door"
 * ```
 *
 * `tags` is comma-separated; `priority` (int, default 4), `attach` (snapshot URL), `id` and
 * `then` (`reply-menu`) are optional. See the debug manifest for why this exists and who can reach it.
 */
@AndroidEntryPoint
class DebugPushReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: PushNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val msg = NtfyMessage(
            id = intent.getStringExtra("id") ?: System.nanoTime().toString(),
            title = intent.getStringExtra("title") ?: "Test alert",
            body = intent.getStringExtra("body") ?: "",
            tags = intent.getStringExtra("tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            priority = intent.getIntExtra("priority", 4),
            click = intent.getStringExtra("click"),
            attachUrl = intent.getStringExtra("attach"),
        )
        // show() fetches the snapshot on the calling thread, which must not be the main one.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notifier.show(msg)
                // `--es then reply-menu`: the doorbell's reply buttons, drawn without pressing
                // "Reply" (the doorbell id is the message id's hash; see PushNotifier.notificationId).
                val camera = PushRoute.cameraOf(msg)
                if (intent.getStringExtra("then") == "reply-menu" && camera != null) {
                    notifier.showReplyMenu(msg.id.hashCode(), camera)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

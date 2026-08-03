package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.QUICK_REPLIES
import com.hawksnest.core.logic.QuickReply
import com.hawksnest.ui.theme.HawksnestTheme
import kotlinx.coroutines.launch

/** Where a tapped reply has got to. Failure is a visible, terminal state — never a silent no-op. */
private sealed interface ReplyState {
    data object Idle : ReplyState
    data class Sending(val id: String) : ReplyState
    data class Sent(val id: String) : ReplyState
    data class Failed(val id: String) : ReplyState
}

/**
 * Prerecorded messages, played out of the camera's own speaker.
 *
 * The audio never touches the phone. go2rtc reads the file from its config volume and pushes it
 * into the camera's audio backchannel — so this is one HTTP call, with no microphone permission,
 * no peer connection and no 2–4s negotiation. See `quickReplyPath`.
 *
 * **The result is always shown.** A reply that fails quietly is worse than no button at all,
 * because the user walks away believing they said something to whoever is at the door. Failure
 * stays on screen until dismissed rather than reverting to Idle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplySheet(
    cameraName: String,
    displayName: String,
    viewModel: CameraPlayerViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ReplyState>(ReplyState.Idle) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("replySheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Quick reply",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Plays out loud on $displayName.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            QUICK_REPLIES.forEach { reply ->
                ReplyRow(
                    reply = reply,
                    state = state,
                    onClick = {
                        // Ignore taps while one is in flight: two overlapping pushes to the same
                        // speaker would talk over each other.
                        if (state !is ReplyState.Sending) {
                            state = ReplyState.Sending(reply.id)
                            scope.launch {
                                val ok = viewModel.sendQuickReply(cameraName, reply)
                                state = if (ok) ReplyState.Sent(reply.id) else ReplyState.Failed(reply.id)
                            }
                        }
                    },
                )
            }
            (state as? ReplyState.Failed)?.let {
                Text(
                    "Couldn't play that. The camera may be unreachable, or the message file is " +
                        "missing on the server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HawksnestTheme.pulse.streak,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplyRow(reply: QuickReply, state: ReplyState, onClick: () -> Unit) {
    val pulse = HawksnestTheme.pulse
    val sending = state is ReplyState.Sending && state.id == reply.id
    val sent = state is ReplyState.Sent && state.id == reply.id
    val failed = state is ReplyState.Failed && state.id == reply.id
    val bg = when {
        sent -> pulse.recoveryDim
        failed -> pulse.streakDim
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        sent -> pulse.recovery
        failed -> pulse.streak
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .testTag("reply_${reply.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            reply.label,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        when {
            sending -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            sent -> Text("played", style = MaterialTheme.typography.labelSmall, color = fg)
            failed -> Text("failed", style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

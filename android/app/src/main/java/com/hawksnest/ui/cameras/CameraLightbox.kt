package com.hawksnest.ui.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.ui.home.CameraUi

/**
 * Full-screen camera view hosting the Ring-style [CameraPlayer] (live + timeline scrubber +
 * transport + in-player switcher). Mounts only while open; dismisses on the close button or the
 * system back. Tracks the switched-to camera locally so the player can change feeds without
 * closing. Mirrors the web `CameraLightbox`.
 *
 * Rendered as a plain overlay in the activity's own window (at the nav-graph root), NOT a Compose
 * `Dialog`: a Dialog is a separate window, and the system picture-in-picture surface shows only
 * the activity's window — a Dialog-hosted player would vanish the moment the app minimized. The
 * Dialog used to supply back-dismiss and status-bar insets for free; the [BackHandler] and
 * [statusBarsPadding] here are their replacements.
 */
@Composable
fun CameraLightbox(
    cameras: List<CameraUi>,
    initial: CameraUi,
    onDismiss: () -> Unit,
    /** Bumped per open (see [CameraSession.Open.nonce]) so a doorbell push that retargets an
     *  already-open lightbox resets the switched-to camera below. */
    nonce: Int = 0,
    /** Frigate event to open on, from a tapped camera alert. Null = open live. */
    initialEventId: String? = null,
    /** True while the activity is minimized into PiP: only the video frame should show, so the
     *  close chrome hides and the player fills the window edge to edge. */
    inPip: Boolean = false,
    viewModel: CameraPlayerViewModel = hiltViewModel(),
) {
    // Registered before CameraPlayer so its own BackHandler (fullscreen exit), registered later
    // in composition and therefore higher priority, still wins while fullscreen.
    BackHandler { onDismiss() }
    var current by remember(initial.id, nonce) { mutableStateOf(initial) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        CameraPlayer(
            cam = current,
            cameras = cameras.ifEmpty { listOf(initial) },
            onSelectCamera = { current = it },
            // Only honour the deep-linked event on the camera the tap named —
            // switching cameras inside the lightbox should land live, not on an
            // unrelated camera's timeline at that timestamp.
            initialEventId = initialEventId.takeIf { current.id == initial.id },
            viewModel = viewModel,
            // In PiP the window IS the video (its aspect is set from the source), so the
            // page padding would render as a black border around a tiny picture.
            modifier = if (inPip) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            },
        )
        if (!inPip) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

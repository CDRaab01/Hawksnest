package com.hawksnest.ui.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hawksnest.core.logic.CameraStart
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
 * Dialog used to supply back-dismiss and status-bar insets for free; the [BackHandler] and the
 * system-bar padding here are their replacements.
 *
 * The player is laid out top-down (controls, picture, timeline) and told how much room it has
 * ([CameraPlayer]'s `viewport`), so the picture gets every bit of height the rest leaves free. It
 * used to be centred at its own shape, which left a 3.5:1 panorama as a thin strip between two
 * black bands, and pinch-zoom trapped inside that strip.
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
    /** How the requested camera starts (a notification's "Talk" opens the mic). Like
     *  [initialEventId], it applies to that camera only; switching lands live. */
    initialStart: CameraStart = CameraStart.LIVE,
    /** True while the activity is minimized into PiP: only the video frame should show, so the
     *  close chrome hides and the player fills the window edge to edge. */
    inPip: Boolean = false,
    viewModel: CameraPlayerViewModel = hiltViewModel(),
) {
    // Registered before CameraPlayer so its own BackHandler (fullscreen exit), registered later
    // in composition and therefore higher priority, still wins while fullscreen.
    BackHandler { onDismiss() }
    var current by remember(initial.id, nonce) { mutableStateOf(initial) }
    // Held here rather than in the player: the player is remounted for every camera (see the key
    // below), and switching cameras while fullscreen should stay fullscreen. The effect lives here
    // for the same reason, or every switch would rotate out of landscape and straight back.
    var fullscreen by remember { mutableStateOf(false) }
    FullscreenEffect(fullscreen && !inPip)
    // Fullscreen and PiP are the picture alone, edge to edge: no scrolling, no page padding.
    val immersive = fullscreen || inPip

    // Opaque: at 96% Home's tiles and room card showed through below the controls, which read as
    // a second, ghostly screen rather than a player.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val bars = WindowInsets.systemBars.asPaddingValues()
        // The room the player's column has: the screen, less the system bars, the close-button
        // row above and the page padding around.
        val viewport = DpSize(
            width = maxWidth - PAGE_PADDING * 2,
            height = maxHeight - bars.calculateTopPadding() - bars.calculateBottomPadding() -
                CLOSE_ROW - PAGE_PADDING,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Scrolls only when it has to (a PTZ panel or reply sheet open on a short screen):
                // the player never shrinks the picture below its own shape to fit.
                .then(if (immersive) Modifier else Modifier.verticalScroll(rememberScrollState())),
            contentAlignment = if (immersive) Alignment.Center else Alignment.TopCenter,
        ) {
            // One player per camera. Switching (the in-player switcher, or a doorbell push that
            // retargets an open lightbox) must tear the old camera's player down and mount a fresh
            // one. Recomposing the same player with a new `cam` left per-camera state behind: the
            // stream views bind their player or renderer once, in AndroidView's factory, so the new
            // camera's stream connected and played into a surface nobody showed, while the old
            // camera's last frame stayed on screen under the new camera's name, labelled Live.
            // Keyed on the open's nonce too: a second tap that opens the SAME camera differently (the
            // notification's "Talk" while it is already showing live) must start a fresh player.
            key(current.id, nonce) {
                CameraPlayer(
                    cam = current,
                    cameras = cameras.ifEmpty { listOf(initial) },
                    onSelectCamera = { current = it },
                    // Only honour the deep-linked event on the camera the tap named —
                    // switching cameras inside the lightbox should land live, not on an
                    // unrelated camera's timeline at that timestamp.
                    initialEventId = initialEventId.takeIf { current.id == initial.id },
                    startWith = if (current.id == initial.id) initialStart else CameraStart.LIVE,
                    fullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    viewport = if (immersive) null else viewport,
                    viewModel = viewModel,
                    // In PiP the window IS the video (its aspect is set from the source), so the
                    // page padding would render as a black border around a tiny picture; fullscreen
                    // is the same picture-only case.
                    modifier = if (immersive) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .systemBarsPadding()
                            .padding(
                                start = PAGE_PADDING,
                                end = PAGE_PADDING,
                                top = CLOSE_ROW,
                                bottom = PAGE_PADDING,
                            )
                    },
                )
            }
        }
        // Hidden in PiP (nothing to tap in a 2-inch window) and in fullscreen, where the picture
        // is the screen and the fullscreen chrome carries its own way out.
        if (!immersive) {
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

/** Side and bottom padding around the player. */
private val PAGE_PADDING = 16.dp

/** Room above the player for the close button (48 dp target plus its 8 dp inset). */
private val CLOSE_ROW = 56.dp

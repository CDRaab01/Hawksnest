package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay

/** The placeholder fill for a camera with no signed snapshot (demo / offline). */
private val PLACEHOLDER = Brush.verticalGradient(listOf(Color(0xFF2A2F37), Color(0xFF0E1116)))

/**
 * A camera snapshot frame (Coil), or the neutral placeholder gradient when there's no URL. [model]
 * is the cache-busted snapshot URL — a new value each refresh bucket makes Coil refetch a frame.
 *
 * To avoid the tile flashing black on every refresh, we keep the last successfully-decoded frame
 * painted underneath and only let the new frame replace it once it has loaded. A failed refresh
 * leaves the last good frame in place rather than blanking to black.
 */
@Composable
fun CameraSnapshot(model: String?, modifier: Modifier = Modifier) {
    if (model == null) {
        Box(modifier.background(PLACEHOLDER))
        return
    }
    var lastLoaded by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Box(modifier.background(PLACEHOLDER)) {
        // Base layer: the previous decoded frame, held until the new one loads (served from Coil's
        // memory cache, so it paints instantly). Null on first paint → the neutral gradient shows.
        lastLoaded?.let { prev ->
            AsyncImage(
                model = prev,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // Foreground: the incoming frame. It paints nothing until it succeeds, so the base frame
        // stays visible meanwhile; on success we promote it to become the new base. On error with
        // no frame ever decoded we flag it so the tile shows "No signal" instead of a silent blank.
        AsyncImage(
            model = model,
            contentDescription = "Camera snapshot",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> { lastLoaded = model; failed = false }
                    is AsyncImagePainter.State.Error -> if (lastLoaded == null) failed = true
                    else -> Unit
                }
            },
        )
        // No frame has ever decoded and the latest fetch failed (offline / waking stream returned
        // nothing) → surface it rather than leaving a frozen blank tile. Restores the offline
        // affordance the web CameraTile has but the Android port had dropped.
        if (failed && lastLoaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No signal", color = Color(0xFF7A828C))
            }
        }
    }
}

/** Append a coarse time bucket so an image view refetches a fresh frame (token preserved). */
fun bustCache(url: String?, bucket: Long): String? {
    if (url == null) return null
    val sep = if (url.contains("?")) "&" else "?"
    return "$url${sep}_=$bucket"
}

/**
 * A snapshot tile that refreshes itself on its own [intervalMs] beat. The ticking state lives here,
 * so it recomposes only this composable — not whatever hosts it. That keeps a sibling live video
 * feed from being recomposed (and hitching) every time the snapshot refresh ticks.
 */
@Composable
fun RefreshingSnapshot(url: String?, modifier: Modifier = Modifier, intervalMs: Long = 10_000L) {
    // Monotonic counter (not wall-clock-derived) so a backward system-clock jump can't repeat a
    // bucket and serve a stale frame. Each tick is a distinct cache-buster → a real refetch.
    val bucket by produceState(0L, url) {
        while (true) {
            delay(intervalMs)
            value += 1
        }
    }
    CameraSnapshot(model = bustCache(url, bucket), modifier = modifier)
}

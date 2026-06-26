package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/** The placeholder fill for a camera with no signed snapshot (demo / offline). */
private val PLACEHOLDER = Brush.verticalGradient(listOf(Color(0xFF2A2F37), Color(0xFF0E1116)))

/**
 * A camera snapshot frame (Coil), or the neutral placeholder gradient when there's no URL. [model]
 * is the cache-busted snapshot URL — a new value each refresh bucket makes Coil refetch a frame.
 */
@Composable
fun CameraSnapshot(model: String?, modifier: Modifier = Modifier) {
    if (model == null) {
        Box(modifier.background(PLACEHOLDER))
    } else {
        AsyncImage(
            model = model,
            contentDescription = "Camera snapshot",
            modifier = modifier.background(Color.Black),
            contentScale = ContentScale.Crop,
        )
    }
}

/** Append a coarse time bucket so an image view refetches a fresh frame (token preserved). */
fun bustCache(url: String?, bucket: Long): String? {
    if (url == null) return null
    val sep = if (url.contains("?")) "&" else "?"
    return "$url${sep}_=$bucket"
}

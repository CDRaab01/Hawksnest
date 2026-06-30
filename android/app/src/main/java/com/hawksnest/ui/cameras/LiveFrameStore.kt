package com.hawksnest.ui.cameras

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The most recent frame captured from a camera's WebRTC live view while it was on screen, keyed by
 * the logical camera id ([com.hawksnest.ui.home.CameraUi.id]).
 *
 * Why this exists: ring-mqtt only snapshots an idle camera on its own slow interval (or on motion),
 * so a grid tile's `_snapshot` can stay stale for minutes even though the user just watched the
 * camera live. When live WebRTC is up we already have the exact current frames on the device — we
 * grab one and stash it here so the tile updates to "the frame I just saw live" the moment the user
 * returns to the grid.
 *
 * Compose-observable (a [mutableStateMapOf]) so a tile reading [get] recomposes when [put] runs.
 * Process-lifetime only (intentionally not persisted) — a frame is a transient "what it looked like
 * just now", not a durable asset; it's replaced on the next view and gone on app restart.
 */
object LiveFrameStore {
    private val frames = mutableStateMapOf<String, ImageBitmap>()

    fun put(cameraId: String, frame: ImageBitmap) {
        frames[cameraId] = frame
    }

    fun get(cameraId: String): ImageBitmap? = frames[cameraId]
}

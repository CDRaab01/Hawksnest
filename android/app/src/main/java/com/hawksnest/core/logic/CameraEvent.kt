package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/**
 * Recorded-camera events + footage URLs — the data behind the Ring-style timeline scrubber.
 * Ported 1:1 from `src/lib/cameraEvents.ts` (with its test suite). Live data comes from Frigate
 * behind Home Assistant (`/api/frigate/…`, reached same-origin through the nginx proxy); demo mode
 * synthesizes events and points the footage URIs at the bundled `R.raw.camera_loop` clip.
 */

/** Frigate's recording/clip views, proxied same-origin through HA's nginx. */
const val FRIGATE_BASE = "/api/frigate"

/** Sentinel the demo source returns for "live"/recorded footage; the player maps it to the
 *  bundled raw resource (`R.raw.camera_loop`). Mirrors the web's `DEMO_CLIP_URL`. */
const val DEMO_CLIP_URI = "demo://camera-loop"

/** One normalized recorded event (a motion/object detection with a clip). */
data class CameraEvent(
    val id: String,
    val camera: String,
    val label: String,
    val startMs: Long,
    /** Event end, epoch ms, or null while the event is still ongoing. */
    val endMs: Long?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
    val thumbnailUrl: String?,
    val snapshotUrl: String?,
)

private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

/** Frigate sends times as epoch *seconds* (float). Normalize to ms; null when absent/invalid. */
private fun secondsToMs(p: JsonPrimitive?): Long? = p?.doubleOrNull?.let { (it * 1000).roundToLong() }

/** Recorded-footage (HLS VOD) URL for [camera] over `[startMs, endMs]` — what the scrubber loads
 *  on seek. Pure builder; [base] is swappable for tests/demo. */
fun recordingUrlAt(camera: String, startMs: Long, endMs: Long, base: String = FRIGATE_BASE): String =
    "$base/vod/$camera/start/${startMs / 1000}/end/${endMs / 1000}/master.m3u8"

/** The downloadable clip (mp4) for a single recorded event. Pure builder. */
fun eventClipUrl(eventId: String, base: String = FRIGATE_BASE): String =
    "$base/notifications/$eventId/clip.mp4"

/** The snapshot (jpg) for a single recorded event. Pure builder. */
fun eventSnapshotUrl(eventId: String, base: String = FRIGATE_BASE): String =
    "$base/notifications/$eventId/snapshot.jpg"

/**
 * Normalize Frigate's events payload into typed, **chronological (oldest-first)** [CameraEvent]s —
 * the order a left-to-right timeline and prev/next stepping want. Entries without an id or a usable
 * start time are dropped. A missing `end_time` means the event is still in progress (`endMs = null`).
 * Ported from `cameraEvents.ts normalizeFrigateEvents`.
 */
fun normalizeFrigateEvents(raw: List<JsonObject>, base: String = FRIGATE_BASE): List<CameraEvent> =
    raw.mapNotNull { e ->
        val id = e.prim("id")?.contentOrNull
        val startMs = secondsToMs(e.prim("start_time"))
        if (id == null || startMs == null) return@mapNotNull null
        val hasSnapshot = e.prim("has_snapshot")?.booleanOrNull ?: false
        CameraEvent(
            id = id,
            camera = e.prim("camera")?.contentOrNull ?: "",
            label = e.prim("label")?.contentOrNull ?: "motion",
            startMs = startMs,
            endMs = secondsToMs(e.prim("end_time")),
            hasClip = e.prim("has_clip")?.booleanOrNull ?: false,
            hasSnapshot = hasSnapshot,
            thumbnailUrl = if (hasSnapshot) eventSnapshotUrl(id, base) else null,
            snapshotUrl = if (hasSnapshot) eventSnapshotUrl(id, base) else null,
        )
    }.sortedBy { it.startMs }

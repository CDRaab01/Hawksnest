package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.stringAttr

/**
 * Camera URL helpers — read HA's signed `entity_picture` live (the token rotates, so never cache
 * it) and derive the snapshot / MJPEG-stream URLs from it, resolved against the connected HA origin.
 * Ported 1:1 from `src/lib/cameraUrl.ts` (with its test suite).
 */

private const val PROXY = "/api/camera_proxy/"
private const val STREAM = "/api/camera_proxy_stream/"
private val UNAVAILABLE = setOf("unavailable", "unknown")

/** Resolve a root-relative HA path against [baseUrl]; absolute paths / empty base are left alone. */
private fun withBase(path: String, baseUrl: String?): String {
    if (baseUrl.isNullOrEmpty() || !path.startsWith("/")) return path
    return baseUrl.trimEnd('/') + path
}

/** The signed snapshot URL off `entity_picture`, or null (demo / non-camera). */
fun snapshotUrl(entity: HassEntity, baseUrl: String? = null): String? {
    val pic = entity.stringAttr("entity_picture")
    if (pic.isNullOrEmpty()) return null
    return withBase(pic, baseUrl)
}

/**
 * Which cache-buster a camera should use, given the two app-wide counters.
 *
 * `shared` ticks on the ~10s beat while the app is foregrounded; `onOpen` is that beat
 * plus one tick per app-open. Frigate cameras take `onOpen` so a reopened app never
 * shows the frame it was left on; Ring cameras stay on `shared`, because its proxy is
 * metered and `hawksnest_ring_snapshot_policy` pins battery cameras to a 300s snapshot
 * interval — refetching on open would spend a metered request for the same image.
 *
 * The 1:1 twin of `useSnapshotBucket` in `src/components/snapshotBucketContext.ts`.
 */
fun snapshotBucket(isFrigate: Boolean, shared: Long, onOpen: Long): Long =
    if (isFrigate) onOpen else shared

/** Snapshot URL with a coarse cache-buster bucket appended so an image view refetches a frame. */
fun snapshotUrlAt(entity: HassEntity, bucket: Long, baseUrl: String? = null): String? {
    val base = snapshotUrl(entity, baseUrl) ?: return null
    val sep = if (base.contains("?")) "&" else "?"
    return "$base${sep}_=$bucket"
}

/** The MJPEG live-stream URL, derived from the snapshot path (reusing the same signed token). */
fun streamUrl(entity: HassEntity, baseUrl: String? = null): String? {
    val pic = entity.stringAttr("entity_picture")
    if (pic == null || !pic.contains(PROXY)) return null
    return withBase(pic.replace(PROXY, STREAM), baseUrl)
}

/** A camera we can actually render a frame for (has a signed URL, not down). */
fun isCameraLive(entity: HassEntity): Boolean =
    entity.state !in UNAVAILABLE && snapshotUrl(entity) != null

package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turn a ring-mqtt **event selector** (`select.<base>_event_select`) into
 * `CameraEvent`s for the timeline. ring-mqtt lists the last ~5 motion/ding/
 * on-demand events as the select's `options`; choosing one plays that recording
 * through the `camera.<base>_event` stream. We parse a kind word (Motion/Ding)
 * and fall back to even spacing for the timestamp (the exact option format is set
 * by ring-mqtt). Returned oldest-first. Ported from `src/lib/ringEvents.ts`.
 */
fun ringEventsFromSelect(
    select: HassEntity?,
    cameraName: String,
    nowMs: Long,
): List<CameraEvent> {
    val options = select?.attributes?.get("options") as? JsonArray ?: return emptyList()
    return options
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotEmpty() }
        .mapIndexed { i, opt ->
            val label = when {
                opt.contains("ding", ignoreCase = true) -> "ding"
                opt.contains("motion", ignoreCase = true) -> "motion"
                else -> "event"
            }
            CameraEvent(
                id = opt,
                camera = cameraName,
                label = label,
                startMs = nowMs - i * 6 * 60_000L,
                endMs = null,
                hasClip = true,
                hasSnapshot = false,
                thumbnailUrl = null,
                snapshotUrl = null,
            )
        }
        .sortedBy { it.startMs }
}

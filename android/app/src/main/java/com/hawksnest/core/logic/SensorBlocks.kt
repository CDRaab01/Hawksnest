package com.hawksnest.core.logic

import com.hawksnest.core.ha.HistoryPoint

/**
 * "Moments of action" for the Ring-style timeline, recovered from a motion/ding **binary_sensor's**
 * state history. ring-mqtt's event selector only surfaces the last ~5 *playable* clips, so on its own
 * the timeline reads nearly empty; HA's recorder, however, has the whole day of motion on/off
 * transitions. This folds that history into duration blocks so the day fills with activity the way
 * Ring's does — then [mergePlayable] marks the handful that still have a recording.
 *
 * Pure + exhaustively unit-tested; mirrors the web `src/lib/motionBlocks.ts` 1:1.
 */

private const val MOTION_ON = "on"

/** Default coalescing gap: motion pulses within a minute of each other read as one "moment" (Ring
 *  groups nearby motion into a single event rather than a picket fence of flickers). */
const val DEFAULT_MERGE_GAP_MS = 60_000L

private data class Run(val start: Long, var end: Long?)

/**
 * Fold a binary_sensor's [points] (`{t, state}`) into duration [CameraEvent] blocks: a block spans
 * each "on" run, and runs separated by a gap shorter than [mergeGapMs] coalesce into one. A run still
 * "on" at the end of history is left open (`endMs = null`, "ongoing"). Blocks carry `hasClip = false`
 * — they are markers; playable clips are overlaid separately by [mergePlayable]. Non-"on" states
 * (`off`/`unavailable`/`unknown`) all count as "not active". Returned oldest-first.
 */
fun motionBlocksFromHistory(
    points: List<HistoryPoint>,
    cameraName: String,
    label: String = "motion",
    mergeGapMs: Long = DEFAULT_MERGE_GAP_MS,
): List<CameraEvent> {
    if (points.isEmpty()) return emptyList()
    val sorted = points.sortedBy { it.t }

    // Raw on-runs: [start, end?] where end == null means "still on at the end of history".
    val runs = mutableListOf<Run>()
    var openStart: Long? = null
    for (p in sorted) {
        val on = p.state.equals(MOTION_ON, ignoreCase = true)
        if (on && openStart == null) {
            openStart = p.t
        } else if (!on && openStart != null) {
            runs.add(Run(openStart, p.t))
            openStart = null
        }
    }
    if (openStart != null) runs.add(Run(openStart, null))
    if (runs.isEmpty()) return emptyList()

    // Coalesce runs whose off-gap is under mergeGapMs (an ongoing run can only be the last).
    val merged = mutableListOf<Run>()
    for (r in runs) {
        val last = merged.lastOrNull()
        val lastEnd = last?.end
        if (last != null && lastEnd != null && r.start - lastEnd < mergeGapMs) {
            last.end = r.end
        } else {
            merged.add(Run(r.start, r.end))
        }
    }

    return merged.map { r ->
        CameraEvent(
            id = "$label:${r.start}",
            camera = cameraName,
            label = label,
            startMs = r.start,
            endMs = r.end,
            hasClip = false,
            hasSnapshot = false,
            thumbnailUrl = null,
            snapshotUrl = null,
        )
    }
}

/**
 * Overlay the playable ring clips ([playable] — the real `Motion N` handles at their true times) onto
 * the motion [blocks]: a block whose span contains a clip's time becomes playable (`hasClip = true`,
 * its `id` swapped to the clip's option handle so the player can select + stream it). Each clip is
 * matched at most once. Playable clips that fall in no block are appended as their own blocks, so a
 * recent clip always shows even when the recorder kept no motion-sensor row for it ("show all, play
 * recent"). Pure; returned oldest-first.
 */
fun mergePlayable(
    blocks: List<CameraEvent>,
    playable: List<CameraEvent>,
    matchWindowMs: Long = DEFAULT_MERGE_GAP_MS,
): List<CameraEvent> {
    val used = HashSet<String>()
    val marked = blocks.map { b ->
        val end = b.endMs ?: b.startMs
        val clip = playable.firstOrNull { c ->
            c.id !in used && c.startMs in (b.startMs - matchWindowMs)..(end + matchWindowMs)
        }
        if (clip != null) {
            used.add(clip.id)
            b.copy(id = clip.id, hasClip = true)
        } else {
            b
        }
    }
    val leftovers = playable.filter { it.id !in used }
    return (marked + leftovers).sortedBy { it.startMs }
}

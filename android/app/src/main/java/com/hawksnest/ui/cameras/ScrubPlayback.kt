package com.hawksnest.ui.cameras

import com.hawksnest.core.logic.TimeRange

/**
 * Pure rules for how the recorded player follows a timeline drag (ExoPlayer-side, Android-only:
 * the web player is hls.js on a `<video>` whose seek/buffer model is different, so there is no
 * `src/lib` twin to keep in step).
 *
 * Why these exist — measured 2026-10-30 against the real Frigate VOD: every camera's HLS segment is
 * ~60 s / ~1.4 MB, the playlist does not declare independent segments (so ExoPlayer cannot snap a
 * seek to a keyframe), and the timeline streamed `onScrub` on every pointer event. Each event was
 * an exact `seekTo`, each seek cancelled the segment load the previous one started, and nothing
 * finished until the finger lifted. Crossing a 2 h page boundary mid-drag additionally dropped
 * the player, re-signed, and built a new ExoPlayer — once per page crossed.
 */

/**
 * Minimum spacing between seeks while a drag is in flight. Long enough that a seek can land part
 * of a segment before the next one cancels it, short enough to still read as a live preview.
 */
const val SCRUB_SEEK_INTERVAL_MS: Long = 150L

/**
 * How long to hold a seek so drag-driven seeks are spaced [SCRUB_SEEK_INTERVAL_MS] apart (leading
 * and trailing edge: the first move seeks immediately, the last one always lands). A settled
 * (non-scrub) seek — the release, a chip tap, prev/next — never waits.
 */
fun scrubSeekDelayMs(nowMs: Long, lastSeekAtMs: Long?, scrubbing: Boolean): Long {
    if (!scrubbing || lastSeekAtMs == null) return 0L
    return (SCRUB_SEEK_INTERVAL_MS - (nowMs - lastSeekAtMs)).coerceIn(0L, SCRUB_SEEK_INTERVAL_MS)
}

/**
 * The playhead the VOD PAGE follows. During a drag the page stays where the drag began
 * ([dragStartHeadMs]) and turns once, on release; otherwise it follows the playhead. Turning
 * per crossing paid a sign round trip + a fresh ExoPlayer + a cold manifest for every 2 h the
 * finger passed over, for pages the user never stopped on.
 */
fun pageHeadMs(headMs: Long, scrubbing: Boolean, dragStartHeadMs: Long?): Long =
    if (scrubbing && dragStartHeadMs != null) dragStartHeadMs else headMs

/**
 * Seek offset (ms into the media) for [headMs] in a page that starts at [page].startMs. While a
 * drag runs past the edge of the held page the preview clamps to that edge rather than seeking
 * outside the media; the release turns the page and seeks exactly.
 */
fun seekOffsetInPage(headMs: Long, page: TimeRange): Long =
    headMs.coerceIn(page.startMs, maxOf(page.startMs, page.endMs)) - page.startMs

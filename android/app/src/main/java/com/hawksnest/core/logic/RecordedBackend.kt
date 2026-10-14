package com.hawksnest.core.logic

/**
 * Which backend, if any, holds a camera's **recorded** footage. This is the split
 * that used to be one boolean (`val isRing = cam.eventSelectId != null`) doing
 * three unrelated jobs: choosing the recorded-event source, deciding whether
 * playback is a per-clip resolution or a seekable VOD, and gating the go2rtc live
 * tier. Those came apart the moment a non-Ring camera had real recordings.
 *
 * 1:1 port of `src/lib/recordedBackend.ts` — keep the two in lockstep
 * (ARCHITECTURE.md's platform-parity rule).
 */
enum class RecordedBackend {
    /** ring-mqtt: recorded playback is per-clip, resolved through the event selector. */
    RING,

    /** Frigate NVR: recorded playback is one continuous, seekable VOD over the window. */
    FRIGATE,

    /** No NVR — demo fixtures, or a plain HA camera with nothing recording it. */
    NONE,
}

/**
 * Ring wins whenever it can actually answer. That ordering is not arbitrary: the Ring path is
 * the one with a resolution step, a retry, and signed URLs that expire, and its behaviour is
 * pinned by a regression suite.
 *
 * The one exception is a selector that is **registered but not reporting**. Retiring a Ring
 * camera does not unregister its entities, so a replacement camera on another backend inherits
 * the base name along with a dead `select.<base>_event_select` — and a dead selector's frozen
 * `options` used to be plotted as a timeline of moments that never happened. A dead selector
 * therefore loses to a backend that *can* answer, and only to that: with nothing else recording,
 * Ring still wins, so a ring-only camera whose selector blips `unavailable` (a ring-mqtt restart)
 * keeps its timeline instead of dropping to [RecordedBackend.NONE] — which matters because
 * callers pin this decision for the life of the view.
 *
 * Accepted consequence: a camera that is genuinely *both* a live Ring camera and a Frigate one
 * would fall to Frigate for one session if its selector happened to be down at open. No such
 * camera exists on this rig, Frigate footage is real, and it self-heals on reopen.
 *
 * @param hasRingSelector the camera has a ring-mqtt event-selector entity
 * @param hasFrigateCamera Frigate's integration lists this camera (see [isFrigateCamera] — fails closed)
 * @param ringSelectorLive that selector is reporting, i.e. Ring can still answer for this camera
 */
fun recordedBackendOf(
    hasRingSelector: Boolean,
    hasFrigateCamera: Boolean,
    ringSelectorLive: Boolean = true,
): RecordedBackend =
    when {
        hasRingSelector && (ringSelectorLive || !hasFrigateCamera) -> RecordedBackend.RING
        hasFrigateCamera -> RecordedBackend.FRIGATE
        else -> RecordedBackend.NONE
    }

/**
 * Whether a real NVR holds this camera's footage, so recorded media is genuine and
 * finite: don't loop it, and do report its duration and playback errors.
 *
 * [RecordedBackend.NONE] is the demo/no-NVR case, where the source hands back the
 * same bundled clip for every seek — that one loops, and an "error" on it is
 * meaningless.
 */
fun hasRealRecordings(backend: RecordedBackend): Boolean = backend != RecordedBackend.NONE

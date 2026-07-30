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
 * Ring wins when a camera somehow looks like both. That ordering is not arbitrary:
 * the Ring path is the one with a resolution step, a retry, and signed URLs that
 * expire, and its behaviour is pinned by a regression suite. A camera carrying a
 * ring-mqtt event selector is a Ring camera regardless of what else knows its name.
 *
 * @param hasRingSelector the camera has a ring-mqtt event-selector entity
 * @param hasFrigateCamera Frigate's integration lists this camera (see [isFrigateCamera] — fails closed)
 */
fun recordedBackendOf(hasRingSelector: Boolean, hasFrigateCamera: Boolean): RecordedBackend =
    when {
        hasRingSelector -> RecordedBackend.RING
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

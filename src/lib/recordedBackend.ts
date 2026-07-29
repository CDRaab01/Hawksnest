/**
 * Which backend, if any, holds a camera's **recorded** footage. This is the split
 * that used to be one boolean (`const isRing = camera.eventSelectId !== null`)
 * doing three unrelated jobs: choosing the recorded-event source, deciding whether
 * playback is a per-clip resolution or a seekable VOD, and gating the go2rtc live
 * tier. Those came apart the moment a non-Ring camera had real recordings.
 *
 * Derived per render rather than baked onto `LogicalCamera`, because Frigate
 * membership is only known after an async config fetch and `cameraModel.ts` is
 * deliberately synchronous.
 *
 * Ported 1:1 to `core/logic/RecordedBackend.kt` — keep the two in lockstep
 * (ARCHITECTURE.md's platform-parity rule).
 */
export type RecordedBackend =
  /** ring-mqtt: recorded playback is per-clip, resolved through the event selector. */
  | "ring"
  /** Frigate NVR: recorded playback is one continuous, seekable VOD over the window. */
  | "frigate"
  /** No NVR — demo fixtures, or a plain HA camera with nothing recording it. */
  | "none";

/**
 * Ring wins when a camera somehow looks like both. That ordering is not arbitrary:
 * the Ring path is the one with a resolution step, a retry, and signed URLs that
 * expire, and its behaviour is pinned by a regression suite. A camera carrying a
 * ring-mqtt event selector is a Ring camera regardless of what else knows its name.
 */
export function recordedBackendOf(args: {
  /** The camera has a ring-mqtt event-selector entity. */
  hasRingSelector: boolean;
  /** Frigate's config lists this camera (see `frigate.ts` — fails closed). */
  hasFrigateCamera: boolean;
}): RecordedBackend {
  if (args.hasRingSelector) return "ring";
  if (args.hasFrigateCamera) return "frigate";
  return "none";
}

/**
 * Whether a real NVR holds this camera's footage, so recorded media is genuine and
 * finite: don't loop it, and do report its duration and playback errors.
 *
 * `"none"` is the demo/no-NVR case, where the source hands back the same bundled
 * clip for every seek — that one loops, and an "error" on it is meaningless.
 */
export function hasRealRecordings(backend: RecordedBackend): boolean {
  return backend !== "none";
}

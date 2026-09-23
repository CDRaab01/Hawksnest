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
 * keeps its timeline instead of dropping to `"none"` — which matters because callers pin this
 * decision for the life of the view.
 *
 * Accepted consequence: a camera that is genuinely *both* a live Ring camera and a Frigate one
 * would fall to Frigate for one session if its selector happened to be down at open. No such
 * camera exists on this rig, Frigate footage is real, and it self-heals on reopen.
 */
export function recordedBackendOf(args: {
  /** The camera has a ring-mqtt event-selector entity. */
  hasRingSelector: boolean;
  /** Frigate's config lists this camera (see `frigate.ts` — fails closed). */
  hasFrigateCamera: boolean;
  /** That selector is reporting, i.e. Ring can still answer for this camera. */
  ringSelectorLive?: boolean;
}): RecordedBackend {
  const ringLive = args.ringSelectorLive ?? true;
  if (args.hasRingSelector && (ringLive || !args.hasFrigateCamera)) return "ring";
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

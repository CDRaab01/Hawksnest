import type { HassEntity } from "./ha";
import type { CameraEvent } from "./cameraEvents";

/**
 * The recording ring-mqtt has published for the selector's **current** option.
 *
 * ring-mqtt (5.x) does not create a `camera.<base>_event` entity — when an option
 * is selected it fetches Ring's signed cloud recording and publishes the URL as
 * the selector's `recordingUrl` attribute (an expiring S3 mp4, playable directly).
 * Non-URL sentinels (`<Recording Not Found>`, `<Transcoding in Progress>`) are not
 * playable, so they read as "no URL".
 */
export function ringRecordingUrl(select: HassEntity | undefined): string | null {
  const url = select?.attributes.recordingUrl;
  return typeof url === "string" && /^https?:\/\//i.test(url) ? url : null;
}

/**
 * True when ring-mqtt has said this selection has nothing to play — the event
 * rotated out of Ring's history, or there's no recording for it. Terminal (fail
 * now), unlike `<Transcoding in Progress>`, which resolves into a URL shortly.
 */
export function ringRecordingMissing(select: HassEntity | undefined): boolean {
  const url = select?.attributes.recordingUrl;
  return typeof url === "string" && /Recording Not Found/i.test(url);
}

/** States meaning "this entity is registered but not reporting" (twin of `cameraModel.ts`'s). */
const DEAD_STATES = new Set(["unavailable", "unknown"]);

/**
 * A full ISO-8601 instant embedded in an option string, or null.
 *
 * The zone designator is **required** so both platforms resolve the same string to the same
 * millisecond. Deliberately strict: this used to be `Date.parse` over the option with its leading
 * word stripped, and `Date.parse("1")` — what `"Motion 1"` reduces to — is not `NaN` in V8, it is
 * 2001-01-01. Every ring option was being timed, wrongly, by two decades.
 *
 * 1:1 with `ringOptionTimeMs` in `core/logic/RingEvents.kt`.
 */
const ISO_INSTANT = /\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})/;

/** The real time an option names, if it names one at all. `"Motion 1"` → null. */
export function ringOptionTimeMs(option: string): number | null {
  const iso = ISO_INSTANT.exec(option)?.[0];
  if (iso === undefined) return null;
  const ms = Date.parse(iso);
  return Number.isFinite(ms) ? ms : null;
}

/**
 * The event selector's current options (`Motion 1`, `Ding 1`, …), newest-first, or empty.
 *
 * Empty when the selector is not reporting: HA restores a retired ring-mqtt entity with its last
 * `options` list intact, so a dead selector still *looks* like it has a handful of playable events
 * long after the camera it belonged to was deleted.
 */
export function ringEventOptions(select: HassEntity | undefined): string[] {
  if (select === undefined || DEAD_STATES.has(select.state)) return [];
  const options = select.attributes.options;
  if (!Array.isArray(options)) return [];
  return (options as unknown[]).filter((o): o is string => typeof o === "string" && o.length > 0);
}

/**
 * Build timeline `CameraEvent`s from the selector's current `options` (which stay the playable
 * `Motion N` handles) paired with REAL event times in `timesDesc` (newest-first). Option *i* takes
 * the *i*-th most recent real time. Returned oldest-first to match the timeline's left→right order.
 *
 * **An option with no recoverable time is dropped, not placed.** It used to fall back to
 * `nowMs - i * 6 * 60_000`, which drew invented moments in exactly the same ink as real ones:
 * `camera.front` inherited 30 frozen options from a retired Ring camera and rendered them as a comb
 * of evenly spaced "recordings" that had never happened, each claiming `hasClip: true`. There is no
 * `nowMs` parameter any more — with no "now" in scope, no time can be invented.
 *
 * 1:1 with `ringEventsFromOptions` in `core/logic/RingEvents.kt`.
 */
export function ringEventsFromOptions(
  options: string[],
  timesDesc: number[],
  cameraName: string,
): CameraEvent[] {
  return options
    .map((opt, i): CameraEvent | null => {
      const startMs = timesDesc[i] ?? ringOptionTimeMs(opt);
      if (startMs === null || startMs === undefined) return null;
      return {
        id: opt,
        camera: cameraName,
        label: /ding/i.test(opt) ? "ding" : /motion/i.test(opt) ? "motion" : "event",
        startMs,
        endMs: null,
        hasClip: true,
        hasSnapshot: false,
        thumbnailUrl: null,
        snapshotUrl: null,
        // Descriptions are a Frigate GenAI feature; Ring events never have one.
        description: null,
      };
    })
    .filter((e): e is CameraEvent => e !== null)
    .sort((a, b) => a.startMs - b.startMs);
}

/**
 * The selector's options, timed only by whatever each option string carries itself. The web has no
 * decoded-history seam (Android's `fetchAttributeHistory` has no twin here), so it always recovers
 * from the option string alone — options like `Motion 1` carry no time and contribute nothing.
 */
export function ringEventsFromSelect(
  select: HassEntity | undefined,
  cameraName: string,
): CameraEvent[] {
  return ringEventsFromOptions(ringEventOptions(select), [], cameraName);
}

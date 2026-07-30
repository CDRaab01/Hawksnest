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

/**
 * Turn a ring-mqtt **event selector** (`select.<base>_event_select`) into
 * `CameraEvent[]` for the timeline. ring-mqtt lists the last ~5 motion/ding/
 * on-demand events as the select's `options`; choosing one publishes that
 * recording's URL (see `ringRecordingUrl`). The exact option string is set
 * by ring-mqtt — we parse a kind word (Motion/Ding) and a trailing timestamp when
 * present, and fall back to even spacing when one can't be parsed. Returned
 * oldest-first to match the timeline's left→right order.
 */
export function ringEventsFromSelect(
  select: HassEntity | undefined,
  cameraName: string,
  nowMs: number = Date.now(),
): CameraEvent[] {
  const options = select?.attributes.options;
  if (!Array.isArray(options)) return [];
  return (options as unknown[])
    .filter((o): o is string => typeof o === "string" && o.length > 0)
    .map((opt, i): CameraEvent => {
      const label = /ding/i.test(opt) ? "ding" : /motion/i.test(opt) ? "motion" : "event";
      // Parse a date/time if the option carries one (strip a leading kind word).
      const parsed = Date.parse(opt.replace(/^[A-Za-z\s-]+/, "").trim());
      const startMs = Number.isFinite(parsed) ? parsed : nowMs - i * 6 * 60_000;
      return {
        id: opt,
        camera: cameraName,
        label,
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
    .sort((a, b) => a.startMs - b.startMs);
}

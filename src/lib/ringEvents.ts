import type { HassEntity } from "./ha";
import type { CameraEvent } from "./cameraEvents";

/**
 * Turn a ring-mqtt **event selector** (`select.<base>_event_select`) into
 * `CameraEvent[]` for the timeline. ring-mqtt lists the last ~5 motion/ding/
 * on-demand events as the select's `options`; choosing one plays that recording
 * back through the `camera.<base>_event` stream. The exact option string is set
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
      };
    })
    .sort((a, b) => a.startMs - b.startMs);
}

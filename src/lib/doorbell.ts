import type { HassEntity } from "./ha";
import type { LogicalCamera } from "./cameraModel";

/** A doorbell press surfaced from a camera's `_ding` sensor. */
export interface DoorbellPress {
  cameraId: string;
  name: string;
  /** Epoch ms of the press (the ding sensor's last_changed). */
  whenMs: number;
}

/**
 * The most recent active doorbell press across all cameras — a camera whose
 * `binary_sensor.<base>_ding` is `on` and changed within `windowMs`. ring-mqtt
 * surfaces a doorbell ring as that sensor flipping on; this is the signal the
 * in-app banner + notification ride. Returns null when nothing is ringing.
 */
export function activeDoorbellPress(
  cameras: LogicalCamera[],
  entities: Record<string, HassEntity>,
  nowMs: number = Date.now(),
  windowMs: number = 30_000,
): DoorbellPress | null {
  let best: DoorbellPress | null = null;
  for (const cam of cameras) {
    if (!cam.dingId) continue;
    const ding = entities[cam.dingId];
    if (!ding || ding.state !== "on") continue;
    const when = ding.last_changed ? new Date(ding.last_changed).getTime() : nowMs;
    if (!Number.isFinite(when) || nowMs - when > windowMs) continue;
    if (!best || when > best.whenMs) {
      best = { cameraId: cam.id, name: cam.name, whenMs: when };
    }
  }
  return best;
}

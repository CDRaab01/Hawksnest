import type { HassEntity } from "./ha";
import type { LogicalCamera } from "./cameraModel";

/** A doorbell press surfaced from a camera's ding sensor (`_ding` or `_visitor`). */
export interface DoorbellPress {
  cameraId: string;
  name: string;
  /** Epoch ms of the press (the ding sensor's last_changed). */
  whenMs: number;
}

/**
 * The most recent active doorbell press across all cameras — a camera whose
 * resolved ding sensor is `on` and changed within `windowMs`. ring-mqtt surfaces a
 * ring as `binary_sensor.<base>_ding` and the Reolink integration as `_visitor`;
 * `cameraModel` folds both into `dingId`, so this reads either. This is the signal
 * the in-app banner + notification ride. Returns null when nothing is ringing.
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

import { DoorClosed, Lock, ShieldCheck } from "lucide-react";
import type { OverrideMap } from "../lib/resolve";

/**
 * Project-local label/icon overrides. Seeded from the owner's stock-HA "before"
 * screenshot, where the single Schlage and its Z-Wave diagnostic sensors render
 * with raw, attribute-derived names ("Lock", "Lock Current status …", "Lock
 * Intrusion"). These force the human labels Hawksnest is built to guarantee.
 *
 * In Phase 1 this map remains the highest-priority tier over the live registry.
 */
export const overrides: OverrideMap = {
  "lock.front_door_lock": { name: "Front Door", icon: Lock },
  // HA exposes this contact sensor as "Lock Current status …" — the poster child.
  "binary_sensor.front_door_current_status": {
    name: "Front Door",
    icon: DoorClosed,
  },
  "binary_sensor.front_door_intrusion": {
    name: "Intrusion",
    icon: ShieldCheck,
  },
  "lock.back_door_lock": { name: "Back Door", icon: Lock },
  "light.basement": { name: "Basement Lights" },
  "camera.front_door": { name: "Front Door" },
  "alarm_control_panel.home": { name: "Home Alarm" },
};

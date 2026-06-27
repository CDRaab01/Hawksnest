import type { HassEntity } from "./ha";
import { domainOf } from "./ha";
import { resolveCameras } from "./cameraModel";
import type { Channel } from "../components/PanelCard";

/** Which at-a-glance highlight a room card shows; the UI maps each to an icon. */
export type RoomStat = "unlocked" | "motion" | "lights" | "cameras" | "temperature";

/** One room highlight chip: a stat kind, a short label, and the PULSE channel tinting it. */
export interface RoomHighlight {
  stat: RoomStat;
  label: string;
  channel: Channel;
}

const MOTION_CLASSES = new Set(["motion", "occupancy", "presence"]);
const MAX_HIGHLIGHTS = 4;

/**
 * Compute up to MAX_HIGHLIGHTS meaningful highlights for a room from its (already diagnostics-
 * filtered) entities, in priority order: unlocked locks → motion → lights on → cameras →
 * temperature. Each appears only when present/non-zero, so a quiet room yields an empty list and the
 * card falls back to a plain device count. Mirrors `core/logic/Rooms.kt`.
 */
export function roomHighlights(entities: HassEntity[]): RoomHighlight[] {
  const out: RoomHighlight[] = [];

  const unlocked = entities.filter(
    (e) => domainOf(e.entity_id) === "lock" && e.state !== "locked" && e.state !== "locking",
  ).length;
  if (unlocked > 0) out.push({ stat: "unlocked", label: `${unlocked} unlocked`, channel: "streak" });

  const motion = entities.some(
    (e) =>
      domainOf(e.entity_id) === "binary_sensor" &&
      MOTION_CLASSES.has(String(e.attributes.device_class ?? "")) &&
      e.state === "on",
  );
  if (motion) out.push({ stat: "motion", label: "Motion", channel: "streak" });

  const lightsOn = entities.filter(
    (e) => domainOf(e.entity_id) === "light" && e.state === "on",
  ).length;
  if (lightsOn > 0) out.push({ stat: "lights", label: `${lightsOn} on`, channel: "strength" });

  // resolveCameras collapses ring-mqtt's split entities so the count is per physical camera. Camera
  // count is independent of naming overrides, so pass an empty map (the web signature requires it).
  const cameras = resolveCameras(
    Object.fromEntries(entities.map((e) => [e.entity_id, e])),
    {},
  ).length;
  if (cameras > 0) out.push({ stat: "cameras", label: String(cameras), channel: "effort" });

  const temp = roomTemperature(entities);
  if (temp !== null) out.push({ stat: "temperature", label: `${temp}°`, channel: "effort" });

  return out.slice(0, MAX_HIGHLIGHTS);
}

/** The first usable temperature reading in the room (rounded), or null. */
function roomTemperature(entities: HassEntity[]): number | null {
  const sensor = entities.find(
    (e) =>
      domainOf(e.entity_id) === "sensor" &&
      String(e.attributes.device_class ?? "") === "temperature" &&
      e.state.trim() !== "" &&
      !Number.isNaN(Number(e.state)),
  );
  return sensor ? Math.round(Number(sensor.state)) : null;
}

/**
 * Map an area name to a stable icon key; the UI resolves it to a platform icon. Substring matching so
 * "Bedroom 2"/"Front Room"/"Backyard" land on sensible icons. Mirrors `core/logic/Rooms.kt`.
 */
export function roomIconKey(area: string): string {
  const a = area.toLowerCase();
  if (a.includes("kitchen")) return "kitchen";
  if (a.includes("dining")) return "dining";
  if (a.includes("bath") || a.includes("shower")) return "bath";
  if (a.includes("bed") || a.includes("master") || a.includes("nursery")) return "bedroom";
  if (a.includes("garage")) return "garage";
  if (a.includes("office") || a.includes("study") || a.includes("desk")) return "office";
  if (
    a.includes("living") ||
    a.includes("family") ||
    a.includes("lounge") ||
    a.includes("great room") ||
    a.includes("front room") ||
    a.includes("big room") ||
    a.includes("den")
  )
    return "living";
  if (a.includes("basement") || a.includes("cellar")) return "basement";
  if (a.includes("laundry") || a.includes("utility") || a.includes("mud")) return "laundry";
  if (a.includes("front door") || a.includes("porch") || a.includes("entry") || a.includes("foyer"))
    return "frontdoor";
  if (
    a.includes("back") ||
    a.includes("yard") ||
    a.includes("exterior") ||
    a.includes("outside") ||
    a.includes("patio") ||
    a.includes("deck") ||
    a.includes("garden")
  )
    return "outdoor";
  if (a.includes("security") || a.includes("alarm")) return "security";
  if (a.includes("unassigned")) return "unassigned";
  return "default";
}

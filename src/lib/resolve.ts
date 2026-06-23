import {
  DoorClosed,
  DoorOpen,
  Lock,
  Lightbulb,
  Camera,
  ShieldCheck,
  ToggleRight,
  Thermometer,
  Blinds,
  Clapperboard,
  Fan,
  Activity,
  CircleHelp,
  type LucideIcon,
} from "lucide-react";
import { domainOf, type HassEntity } from "./ha";

/**
 * Per-entity override. Highest-priority tier of the resolution chain — lets the
 * owner force a human label/icon regardless of what HA exposes. This is what
 * turns HA's "Lock Current status …" into "Front Door".
 */
export interface EntityOverride {
  name?: string;
  icon?: LucideIcon;
}

export type OverrideMap = Record<string, EntityOverride>;

/**
 * Label resolution chain (mandatory — never surface a raw entity_id/attribute):
 *   1. per-entity override map
 *   2. HA friendly_name
 *   3. prettified entity_id (strip domain, title-case)
 */
export function resolveName(
  entity: HassEntity,
  overrides: OverrideMap = {},
): string {
  const override = overrides[entity.entity_id]?.name;
  if (override) return override;

  const friendly = entity.attributes.friendly_name?.trim();
  if (friendly) return friendly;

  return prettifyEntityId(entity.entity_id);
}

/** `binary_sensor.front_door_current_status` -> "Front Door Current Status". */
export function prettifyEntityId(entityId: string): string {
  const withoutDomain = entityId.includes(".")
    ? entityId.slice(entityId.indexOf(".") + 1)
    : entityId;
  return withoutDomain
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

/** A small set of mdi:* names we map to Lucide; everything else falls through. */
const MDI_TO_LUCIDE: Record<string, LucideIcon> = {
  "mdi:lock": Lock,
  "mdi:door-open": DoorOpen,
  "mdi:door-closed": DoorClosed,
  "mdi:lightbulb": Lightbulb,
  "mdi:cctv": Camera,
};

const DOMAIN_ICON: Record<string, LucideIcon> = {
  lock: Lock,
  light: Lightbulb,
  camera: Camera,
  image: Camera,
  binary_sensor: Activity,
  sensor: Activity,
  switch: ToggleRight,
  climate: Thermometer,
  cover: Blinds,
  scene: Activity,
  alarm_control_panel: ShieldCheck,
  media_player: Clapperboard,
  fan: Fan,
};

/**
 * Icon resolution chain mirrors labels:
 *   1. per-entity override
 *   2. HA `icon` attribute (mapped mdi -> Lucide where known)
 *   3. domain default
 *   4. neutral fallback (never crash on an unknown domain)
 */
export function resolveIcon(
  entity: HassEntity,
  overrides: OverrideMap = {},
): LucideIcon {
  const override = overrides[entity.entity_id]?.icon;
  if (override) return override;

  const haIcon = entity.attributes.icon;
  if (haIcon && MDI_TO_LUCIDE[haIcon]) return MDI_TO_LUCIDE[haIcon];

  return DOMAIN_ICON[domainOf(entity.entity_id)] ?? CircleHelp;
}

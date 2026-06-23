import { domainOf } from "./ha";
import type { Density } from "../cards/types";

// Interactive control domains get the comfortable, one-primary-action card.
const CONTROL_DOMAINS = new Set([
  "lock",
  "light",
  "switch",
  "alarm_control_panel",
  "cover",
  "climate",
  "media_player",
  "fan",
  "scene",
]);

// Camera/image render as a full-width "feature" tile.
const FEATURE_DOMAINS = new Set(["camera", "image"]);

/** Card density: controls + feature tiles are comfortable; read-only is compact. */
export function cardDensityFor(entityId: string): Density {
  const domain = domainOf(entityId);
  if (FEATURE_DOMAINS.has(domain) || CONTROL_DOMAINS.has(domain)) {
    return "comfortable";
  }
  return "compact";
}

/** Feature tiles span the full grid width (e.g. a camera). */
export function isFeature(entityId: string): boolean {
  return FEATURE_DOMAINS.has(domainOf(entityId));
}

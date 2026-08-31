/**
 * Which camera-movement controls a camera actually has, resolved from the HA
 * entities the **official Reolink integration** creates.
 *
 * Hawksnest reaches the cameras through Home Assistant, never directly: the
 * integration owns the camera's HTTP API session and serialises commands, so the
 * app only presses buttons and sets numbers (`servers enforce, clients present`).
 * Nothing here is Reolink-specific beyond the entity naming — any integration
 * exposing the same shape resolves identically.
 *
 * ## The entity ids are NOT derived from the camera base
 *
 * The obvious implementation — `button.${cameraBase}_ptz_up` — is wrong on this
 * deployment and fails silently. Measured 2026-07-30: the stairway camera's
 * Frigate entity is `camera.first_floor_stairway`, but its Reolink *device* is
 * named `stairway`, so its PTZ entities are `button.stairway_ptz_*`. Deriving ids
 * from the base would have dropped PTZ on that camera with no error — the chrome
 * would simply never appear, which reads as "this camera can't move" rather than
 * as a bug.
 *
 * So the slug is *discovered* from the entities that exist and then matched to the
 * camera, tolerating the alias. The rule is deliberately narrow: an exact match
 * always wins, and otherwise one slug must be the other's **trailing whole
 * segments** — `stairway` matches `first_floor_stairway`, `big` does not match
 * `big_room`. Trailing rather than either-end because that is the shape the real
 * divergence takes (a room name qualified by a floor), and a prefix rule matched
 * far more by accident.
 *
 * Two backstops for what a heuristic can still get wrong: an **ambiguous** match
 * (two plausible candidates) yields null rather than a guess, because pointing the
 * pad at the wrong camera moves the wrong lens; and `aliases` lets a name be
 * pinned explicitly. The residual gap is a camera whose base is exactly the
 * trailing segment of another camera's slug — `camera.room` alongside a `big_room`
 * device would resolve to `big_room`. No such pair exists here; if one appears,
 * pin it in `aliases` rather than loosening this.
 *
 * Ported 1:1 to `core/logic/CameraPtz.kt` — keep the two in lockstep.
 */

/** The entity ids behind one camera's movement controls. Directions are required;
 *  the rest are per-model (the E1 Pro has pan/tilt but no zoom, focus or autofocus). */
export interface PtzControls {
  /** The Reolink device slug these entities are named after — often, but not
   *  always, the camera base. Kept for diagnostics and test readability. */
  slug: string;
  up: string;
  down: string;
  left: string;
  right: string;
  /** Halts a move in progress. Always present when the directions are. */
  stop: string;
  /** Optical zoom (`number`), or null on models without it. */
  zoom: string | null;
  /** Manual focus position (`number`), or null. Meaningful only with autofocus off. */
  focus: string | null;
  /** Autofocus toggle (`switch`), or null. */
  autofocus: string | null;
  /**
   * Saved-position select, or null.
   *
   * Null is the normal state here, not a defect: the integration creates this only
   * once a preset is actually saved **on the camera** (all six slots read
   * `enable: 0` as of 2026-07-30). Save one in the Reolink app and it appears.
   */
  preset: string | null;
}

/** Is one slug the other's trailing whole segments? (`stairway` ~ `first_floor_stairway`) */
export function slugsMatch(a: string, b: string): boolean {
  return a === b || a.endsWith(`_${b}`) || b.endsWith(`_${a}`);
}

/**
 * Pick the integration's device slug for `cameraBase` from the slugs that actually exist.
 *
 * Shared with `doorbellControls.ts` so the two resolvers can never disagree about which
 * device a camera is. The rules, in order: an explicit alias wins, then an exact match
 * (never let a fuzzy match override an exact one), then exactly one trailing-segment
 * match. Ambiguity yields null rather than a guess — pointing a control at the wrong
 * camera is worse than showing no control.
 */
export function pickDeviceSlug(
  cameraBase: string,
  candidates: string[],
  aliases: Record<string, string> = {},
): string | null {
  const alias = aliases[cameraBase];
  if (alias && candidates.includes(alias)) return alias;
  if (candidates.includes(cameraBase)) return cameraBase;
  const near = candidates.filter((c) => slugsMatch(c, cameraBase));
  return near.length === 1 ? near[0] : null;
}

const PTZ_UP = /^button\.(.+)_ptz_up$/;

/**
 * The movement controls for `cameraBase` (the part after `camera.`), or null when
 * the camera has none.
 *
 * @param entityIds every entity id currently known — the candidate slugs are
 *   discovered from it, so a camera whose integration is absent resolves to null.
 * @param aliases optional explicit `cameraBase -> reolink slug` overrides, for the
 *   day a name is too far apart for segment matching to bridge honestly.
 */
export function resolvePtz(
  cameraBase: string,
  entityIds: Iterable<string>,
  aliases: Record<string, string> = {},
): PtzControls | null {
  const ids = entityIds instanceof Set ? entityIds : new Set(entityIds);

  // Candidate slugs = every camera that advertises a PTZ "up" button.
  const candidates: string[] = [];
  for (const id of ids) {
    const m = PTZ_UP.exec(id);
    if (m) candidates.push(m[1]);
  }
  if (candidates.length === 0) return null;

  const slug = pickDeviceSlug(cameraBase, candidates, aliases);
  if (!slug) return null;

  const has = (id: string) => (ids.has(id) ? id : null);
  const down = has(`button.${slug}_ptz_down`);
  const left = has(`button.${slug}_ptz_left`);
  const right = has(`button.${slug}_ptz_right`);
  const stop = has(`button.${slug}_ptz_stop`);
  // Fail closed on a half-present set: a pad that can move but not stop is worse
  // than no pad, because a press would leave the camera panning.
  if (!down || !left || !right || !stop) return null;

  return {
    slug,
    up: `button.${slug}_ptz_up`,
    down,
    left,
    right,
    stop,
    zoom: has(`number.${slug}_zoom`),
    focus: has(`number.${slug}_focus`),
    autofocus: has(`switch.${slug}_auto_focus`),
    preset: has(`select.${slug}_ptz_preset`),
  };
}

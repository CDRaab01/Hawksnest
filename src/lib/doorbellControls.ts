import { pickDeviceSlug } from "./cameraPtz";

/**
 * The doorbell-specific controls a camera has, resolved from the HA entities the
 * **official Reolink integration** creates.
 *
 * Same shape and same discipline as `cameraPtz.ts`, and for the same reason: **the entity
 * ids are NOT derived from the camera base.** The integration names its entities after its
 * own device, which is frequently not the Frigate slug. On this deployment the doorbell's
 * Frigate camera is `camera.front_door_reolink` while its Reolink entities are
 * `..._front_door_front_door_reolink_*` — the integration names them
 * `<host device>_<channel device>_<entity>`, and the host device kept the camera's original
 * name. Deriving `number.${cameraBase}_doorbell_volume` would resolve to nothing, with no
 * error: the controls would simply never appear, which reads as "this doorbell has no
 * settings" rather than as a bug.
 *
 * So the slug is *discovered* from the entities that exist and matched with the shared
 * `pickDeviceSlug` rule (exact wins; otherwise exactly one trailing-segment match;
 * ambiguity yields null rather than a guess).
 *
 * Only doorbells resolve here. A wall camera has no `_doorbell_volume` entity, so it gets
 * null and no chrome — which is what we want, since these controls are meaningless on a
 * camera with no button and no chime.
 *
 * Ported 1:1 to `core/logic/DoorbellControls.kt` — keep the two in lockstep.
 */
export interface DoorbellControls {
  /** The Reolink device slug these entities are named after — often NOT the camera base. */
  slug: string;
  /** Chime/speaker volume (`number`). Required: it is the anchor that proves a doorbell. */
  volume: string;
  /** Whether pressing the button plays a sound at the door (`switch`), or null. */
  buttonSound: string | null;
  /** Message played automatically when someone presses (`select`), or null. */
  autoReply: string | null;
  /** Play a stored message now (`select`), or null. */
  playReply: string | null;
  /**
   * The camera's siren, or null.
   *
   * **This is the `siren` DOMAIN, not ring-mqtt's `switch.<base>_siren`.** They are
   * different entities with different services (`siren.turn_on` vs `switch.turn_on`), which
   * is why this is resolved here rather than through `LogicalCamera.sirenSwitchId` — that
   * field is the ring-mqtt path and stays untouched.
   */
  siren: string | null;
}

/** Only doorbells have a chime volume, so it is the anchor for "this is a doorbell". */
const DOORBELL_VOLUME = /^number\.(.+)_doorbell_volume$/;

/**
 * The doorbell controls for `cameraBase` (the part after `camera.`), or null when the
 * camera is not a doorbell / its integration is absent.
 *
 * @param entityIds every entity id currently known — candidate slugs are discovered from it.
 * @param aliases optional explicit `cameraBase -> reolink slug` overrides, for the day a
 *   name is too far apart for segment matching to bridge honestly.
 */
export function resolveDoorbellControls(
  cameraBase: string,
  entityIds: Iterable<string>,
  aliases: Record<string, string> = {},
): DoorbellControls | null {
  const ids = entityIds instanceof Set ? entityIds : new Set(entityIds);

  const candidates: string[] = [];
  for (const id of ids) {
    const m = DOORBELL_VOLUME.exec(id);
    if (m) candidates.push(m[1]);
  }
  if (candidates.length === 0) return null;

  const slug = pickDeviceSlug(cameraBase, candidates, aliases);
  if (!slug) return null;

  const has = (id: string) => (ids.has(id) ? id : null);
  return {
    slug,
    volume: `number.${slug}_doorbell_volume`,
    buttonSound: has(`switch.${slug}_doorbell_button_sound`),
    autoReply: has(`select.${slug}_auto_quick_reply_message`),
    playReply: has(`select.${slug}_play_quick_reply_message`),
    siren: has(`siren.${slug}_siren`),
  };
}

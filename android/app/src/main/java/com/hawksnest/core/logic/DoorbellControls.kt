package com.hawksnest.core.logic

/**
 * The doorbell-specific controls a camera has, resolved from the HA entities the **official
 * Reolink integration** creates.
 *
 * Same shape and same discipline as [PtzControls], and for the same reason: **the entity ids are
 * NOT derived from the camera base.** The integration names its entities after its own device,
 * which is frequently not the Frigate slug. On this deployment the doorbell's Frigate camera is
 * `camera.front_door_reolink` while its Reolink entities are `..._front_door_front_door_reolink_*`
 * — the integration names them `<host device>_<channel device>_<entity>`, and the host device kept
 * the camera's original name. Deriving `number.${cameraBase}_doorbell_volume` would resolve to
 * nothing, with no error: the controls would simply never appear, which reads as "this doorbell
 * has no settings" rather than as a bug.
 *
 * So the slug is *discovered* from the entities that exist and matched with the shared
 * [pickDeviceSlug] rule (exact wins; otherwise exactly one trailing-segment match; ambiguity
 * yields null rather than a guess).
 *
 * Only doorbells resolve here — a wall camera has no `_doorbell_volume` entity and gets null.
 *
 * Ported 1:1 from `src/lib/doorbellControls.ts` — keep the two in lockstep.
 */
data class DoorbellControls(
    /** The Reolink device slug these entities are named after — often NOT the camera base. */
    val slug: String,
    /** Chime/speaker volume (`number`). Required: it is the anchor that proves a doorbell. */
    val volume: String,
    /** Whether pressing the button plays a sound at the door (`switch`), or null. */
    val buttonSound: String?,
    /** Message played automatically when someone presses (`select`), or null. */
    val autoReply: String?,
    /** Play a stored message now (`select`), or null. */
    val playReply: String?,
    /**
     * The camera's siren, or null.
     *
     * **This is the `siren` DOMAIN, not ring-mqtt's `switch.<base>_siren`.** They are different
     * entities with different services (`siren.turn_on` vs `switch.turn_on`), which is why this is
     * resolved here rather than through [LogicalCamera.sirenSwitchId] — that field is the
     * ring-mqtt path and stays untouched.
     */
    val siren: String?,
)

/** Only doorbells have a chime volume, so it is the anchor for "this is a doorbell". */
private val DOORBELL_VOLUME = Regex("""^number\.(.+)_doorbell_volume$""")

/**
 * The doorbell controls for [cameraBase] (the part after `camera.`), or null when the camera is
 * not a doorbell / its integration is absent.
 *
 * @param entityIds every entity id currently known — candidate slugs are discovered from it.
 * @param aliases optional explicit `cameraBase -> reolink slug` overrides.
 */
fun resolveDoorbellControls(
    cameraBase: String,
    entityIds: Collection<String>,
    aliases: Map<String, String> = emptyMap(),
): DoorbellControls? {
    val ids = entityIds as? Set<String> ?: entityIds.toSet()

    val candidates = ids.mapNotNull { DOORBELL_VOLUME.find(it)?.groupValues?.get(1) }
    if (candidates.isEmpty()) return null

    val slug = pickDeviceSlug(cameraBase, candidates, aliases) ?: return null

    fun has(id: String): String? = if (id in ids) id else null

    return DoorbellControls(
        slug = slug,
        volume = "number.${slug}_doorbell_volume",
        buttonSound = has("switch.${slug}_doorbell_button_sound"),
        autoReply = has("select.${slug}_auto_quick_reply_message"),
        playReply = has("select.${slug}_play_quick_reply_message"),
        siren = has("siren.${slug}_siren"),
    )
}

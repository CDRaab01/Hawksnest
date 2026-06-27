package com.hawksnest.core.logic

import com.hawksnest.core.ha.domainOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lock user-code (keypad PIN) management for the Z-Wave deadbolts. Ported from
 * web `src/lib/lockCodes.ts`.
 *
 * IMPORTANT: these are the PIN codes stored *on the physical lock* (Z-Wave JS
 * user-code slots), NOT an app passcode. Hawksnest has no app-side lock screen /
 * PIN gate for the household — this only manages who can open the physical doors
 * with a keypad code. Slot 1 = Christian, slot 2 = Elizabeth, slots 3+ = guests.
 */

data class CodeSlot(val slot: Int, val label: String)

/** Named owner slots. Guests occupy [FIRST_GUEST_SLOT] and up. */
val OWNER_SLOTS = listOf(CodeSlot(1, "Christian"), CodeSlot(2, "Elizabeth"))

const val FIRST_GUEST_SLOT = 3
const val LAST_GUEST_SLOT = 30

private const val GUEST_AUTOMATION_PREFIX = "hawksnest_guest_"

/** Schlage BE469ZP keypad codes are 4–8 digits. */
fun isValidUserCode(code: String): Boolean = Regex("^\\d{4,8}$").matches(code)

/** Stable per-(lock, slot) automation id, so re-creating a guest overwrites cleanly. */
fun guestAutomationId(lockEntityId: String, slot: Int): String {
    val slug = lockEntityId.replace(Regex("[^a-z0-9]+", RegexOption.IGNORE_CASE), "_").lowercase()
    return "$GUEST_AUTOMATION_PREFIX${slug}_slot$slot"
}

/** True for automations Hawksnest created to expire a guest code. */
fun isGuestAutomation(id: String): Boolean = id.startsWith(GUEST_AUTOMATION_PREFIX)

/** The id prefix of every guest-expiry automation for one lock (for filtering the list). */
fun guestAutomationPrefixFor(lockEntityId: String): String =
    guestAutomationId(lockEntityId, 0).removeSuffix("0")

/** Parse the slot number out of a guest-expiry automation id, or null if it doesn't match. */
fun guestSlotFromId(id: String): Int? {
    if (!isGuestAutomation(id)) return null
    return Regex("_slot(\\d+)$").find(id)?.groupValues?.get(1)?.toInt()
}

/** True if `entityId` is a lock (the only domain these codes apply to). */
fun isLockEntity(entityId: String): Boolean = domainOf(entityId) == "lock"

/** Split a datetime-local value ("YYYY-MM-DDTHH:MM") into HA `HH:MM:SS` + `YYYY-MM-DD HH:MM:SS`. */
private fun splitLocalDateTime(local: String): Pair<String, String>? {
    val m = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})$").find(local) ?: return null
    val (date, time) = m.destructured
    return "$time:00" to "$date $time:00"
}

/**
 * Build the HA automation that expires a guest code: fires daily at the expiry
 * time, gated by a template condition to only run on/after the full expiry
 * datetime, then clears the slot and disables itself (effectively one-shot).
 * Returns null for an invalid `expiryLocal`. Mirrors web `buildGuestExpiryAutomation`.
 *
 * NOTE: relies on HA evaluating the timestamp template in its local timezone —
 * verify against the live instance before depending on it for access control.
 */
fun buildGuestExpiryAutomation(
    lockEntityId: String,
    slot: Int,
    guestName: String,
    expiryLocal: String,
): JsonObject? {
    val parts = splitLocalDateTime(expiryLocal) ?: return null
    val (at, stamp) = parts
    return buildJsonObject {
        put("id", guestAutomationId(lockEntityId, slot))
        put("alias", "Guest code expiry — $guestName (slot $slot)")
        put("mode", "single")
        putJsonArray("trigger") {
            add(buildJsonObject { put("platform", "time"); put("at", at) })
        }
        putJsonArray("condition") {
            add(
                buildJsonObject {
                    put("condition", "template")
                    put("value_template", "{{ as_timestamp(now()) >= as_timestamp('$stamp') }}")
                },
            )
        }
        putJsonArray("action") {
            add(
                buildJsonObject {
                    put("service", "zwave_js.clear_lock_usercode")
                    putJsonObject("target") { put("entity_id", lockEntityId) }
                    putJsonObject("data") { put("code_slot", slot) }
                },
            )
            // Self-disable so a one-shot expiry doesn't re-fire every day afterward.
            add(
                buildJsonObject {
                    put("service", "automation.turn_off")
                    putJsonObject("target") { put("entity_id", "{{ this.entity_id }}") }
                },
            )
        }
    }
}

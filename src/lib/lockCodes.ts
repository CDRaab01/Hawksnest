import { domainOf } from "./ha";
import type { AutomationConfig } from "./automations";

/**
 * Lock user-code (keypad PIN) management for the Z-Wave deadbolts.
 *
 * IMPORTANT: these are the PIN codes stored *on the physical lock* (Z-Wave JS
 * user-code slots), NOT an app passcode. Hawksnest deliberately has no app-side
 * lock screen / PIN gate for the household — this only manages who can open the
 * physical doors with a keypad code.
 *
 * Slot convention (from the deployment spec): slot 1 = Christian, slot 2 =
 * Elizabeth, slots 3+ = guests (time-limited, with HA-driven expiry).
 */

export interface CodeSlot {
  slot: number;
  label: string;
}

/** Named owner slots. Guests occupy {@link FIRST_GUEST_SLOT} and up. */
export const OWNER_SLOTS: CodeSlot[] = [
  { slot: 1, label: "Christian" },
  { slot: 2, label: "Elizabeth" },
];

/** First slot reserved for time-limited guest codes. */
export const FIRST_GUEST_SLOT = 3;

/** Highest slot we'll allocate to a guest (Schlage BE469ZP supports up to 30). */
export const LAST_GUEST_SLOT = 30;

const GUEST_AUTOMATION_PREFIX = "hawksnest_guest_";

/**
 * Schlage BE469ZP keypad codes are 4–8 digits. Reject anything else so we never
 * write a code the lock will silently refuse.
 */
export function isValidUserCode(code: string): boolean {
  return /^\d{4,8}$/.test(code);
}

/** Service-call descriptor (domain/service/data), entity supplied by the caller. */
export interface ServiceCall {
  domain: string;
  service: string;
  data: Record<string, unknown>;
}

/** Set a keypad code in `slot` (writes to the physical lock via Z-Wave JS). */
export function setUserCodeCall(slot: number, code: string): ServiceCall {
  return {
    domain: "zwave_js",
    service: "set_lock_usercode",
    data: { code_slot: slot, usercode: code },
  };
}

/** Clear the keypad code in `slot`. */
export function clearUserCodeCall(slot: number): ServiceCall {
  return {
    domain: "zwave_js",
    service: "clear_lock_usercode",
    data: { code_slot: slot },
  };
}

/** Stable per-(lock, slot) automation id, so re-creating a guest overwrites cleanly. */
export function guestAutomationId(lockEntityId: string, slot: number): string {
  const slug = lockEntityId.replace(/[^a-z0-9]+/gi, "_").toLowerCase();
  return `${GUEST_AUTOMATION_PREFIX}${slug}_slot${slot}`;
}

/** True for automations Hawksnest created to expire a guest code. */
export function isGuestAutomation(id: string): boolean {
  return id.startsWith(GUEST_AUTOMATION_PREFIX);
}

/** The id prefix of every guest-expiry automation for one lock (for filtering the list). */
export function guestAutomationPrefixFor(lockEntityId: string): string {
  // guestAutomationId(lock, N) === `${prefix}${N}`
  return guestAutomationId(lockEntityId, 0).replace(/0$/, "");
}

/** Parse the slot number out of a guest-expiry automation id, or null if it doesn't match. */
export function guestSlotFromId(id: string): number | null {
  const m = id.match(/_slot(\d+)$/);
  return m && isGuestAutomation(id) ? Number(m[1]) : null;
}

/** Split a datetime-local value ("YYYY-MM-DDTHH:MM") into HA `HH:MM:SS` + `YYYY-MM-DD HH:MM:SS`. */
function splitLocalDateTime(local: string): { at: string; stamp: string } | null {
  const m = local.match(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})$/);
  if (!m) return null;
  return { at: `${m[2]}:00`, stamp: `${m[1]} ${m[2]}:00` };
}

/**
 * Build the HA automation that expires a guest code. It fires daily at the
 * expiry time-of-day, but a template condition gates it to only run *on or after*
 * the full expiry datetime; the action clears the slot and then disables itself,
 * so it's effectively one-shot. Returns null if `expiryLocal` isn't a valid
 * `datetime-local` value.
 *
 * NOTE: the expiry relies on HA evaluating the timestamp template in its local
 * timezone — verify against the live instance before depending on it for access.
 */
export function buildGuestExpiryAutomation(args: {
  lockEntityId: string;
  slot: number;
  guestName: string;
  expiryLocal: string;
}): AutomationConfig | null {
  const parts = splitLocalDateTime(args.expiryLocal);
  if (!parts) return null;
  return {
    id: guestAutomationId(args.lockEntityId, args.slot),
    alias: `Guest code expiry — ${args.guestName} (slot ${args.slot})`,
    mode: "single",
    trigger: [{ platform: "time", at: parts.at }],
    condition: [
      {
        condition: "template",
        value_template: `{{ as_timestamp(now()) >= as_timestamp('${parts.stamp}') }}`,
      },
    ],
    action: [
      {
        service: "zwave_js.clear_lock_usercode",
        target: { entity_id: args.lockEntityId },
        data: { code_slot: args.slot },
      },
      // Self-disable so a one-shot expiry doesn't re-fire every day afterward.
      { service: "automation.turn_off", target: { entity_id: "{{ this.entity_id }}" } },
    ],
  };
}

/** True if `entityId` is a lock (the only domain these codes apply to). */
export function isLockEntity(entityId: string): boolean {
  return domainOf(entityId) === "lock";
}

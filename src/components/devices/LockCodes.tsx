import { useMemo, useState } from "react";
import { KeyRound, Trash2, UserPlus } from "lucide-react";
import { PanelCard } from "../PanelCard";
import { PulseButton } from "../PulseButton";
import {
  callService,
  saveAutomationConfig,
  deleteAutomationConfig,
} from "../../store/connection";
import { useAutomationEntities } from "../../store/entityStore";
import {
  OWNER_SLOTS,
  FIRST_GUEST_SLOT,
  LAST_GUEST_SLOT,
  isValidUserCode,
  setUserCodeCall,
  clearUserCodeCall,
  buildGuestExpiryAutomation,
  guestAutomationPrefixFor,
  guestSlotFromId,
} from "../../lib/lockCodes";

async function call(c: { domain: string; service: string; data: Record<string, unknown> }, entityId: string) {
  await callService(c.domain, c.service, { entity_id: entityId, ...c.data });
}

/** One owner slot: enter a PIN to set it, or clear it. */
function OwnerSlotRow({ lockEntityId, slot, label }: { lockEntityId: string; slot: number; label: string }) {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function set() {
    if (!isValidUserCode(code)) {
      setMsg("Enter a 4–8 digit code.");
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      await call(setUserCodeCall(slot, code), lockEntityId);
      setCode("");
      setMsg(`Code set for ${label}.`);
    } catch {
      setMsg("Couldn't reach the lock.");
    } finally {
      setBusy(false);
    }
  }

  async function clear() {
    setBusy(true);
    setMsg(null);
    try {
      await call(clearUserCodeCall(slot), lockEntityId);
      setMsg(`Code cleared for ${label}.`);
    } catch {
      setMsg("Couldn't reach the lock.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="px-lg py-md">
      <div className="flex items-center gap-md">
        <KeyRound className="shrink-0 text-ink-dim" size={18} />
        <div className="min-w-0 flex-1">
          <div className="font-body text-body text-ink">{label}</div>
          <div className="font-mono text-caption text-ink-faint">Slot {slot}</div>
        </div>
        <input
          type="password"
          inputMode="numeric"
          autoComplete="off"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          placeholder="PIN"
          aria-label={`New code for ${label}`}
          className="w-24 rounded-sm border border-hairline bg-panel px-sm py-xs font-mono text-body text-ink"
        />
        <PulseButton variant="tonal" channel="recovery" compact disabled={busy} onClick={set}>
          Set
        </PulseButton>
        <PulseButton variant="ghost" compact disabled={busy} onClick={clear}>
          Clear
        </PulseButton>
      </div>
      {msg && <div className="mt-xs pl-[34px] font-body text-caption text-ink-dim">{msg}</div>}
    </div>
  );
}

/** Add-a-guest form: name + PIN + expiry → set code and create the HA expiry automation. */
function AddGuest({ lockEntityId, nextSlot }: { lockEntityId: string; nextSlot: number | null }) {
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [expiry, setExpiry] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function add() {
    if (nextSlot === null) {
      setMsg("No free guest slots.");
      return;
    }
    if (!name.trim()) {
      setMsg("Enter a name.");
      return;
    }
    if (!isValidUserCode(code)) {
      setMsg("Enter a 4–8 digit code.");
      return;
    }
    const automation = buildGuestExpiryAutomation({
      lockEntityId,
      slot: nextSlot,
      guestName: name.trim(),
      // Expiry is a whole day; the code turns off at 11:59 PM on the chosen date.
      expiryLocal: expiry ? `${expiry}T23:59` : "",
    });
    if (!automation) {
      setMsg("Pick an expiry date.");
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      await call(setUserCodeCall(nextSlot, code), lockEntityId);
      await saveAutomationConfig(automation);
      setName("");
      setCode("");
      setExpiry("");
      setMsg(`Guest "${name.trim()}" added to slot ${nextSlot}.`);
    } catch {
      setMsg("Couldn't add the guest (needs an admin HA token).");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-sm px-lg py-md">
      <div className="flex items-center gap-sm">
        <UserPlus className="shrink-0 text-ink-dim" size={18} />
        <span className="font-body text-body text-ink">
          Add guest{nextSlot !== null ? ` (slot ${nextSlot})` : ""}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-sm pl-[34px]">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Name"
          aria-label="Guest name"
          className="min-w-0 flex-1 rounded-sm border border-hairline bg-panel px-sm py-xs font-body text-body text-ink"
        />
        <input
          type="password"
          inputMode="numeric"
          autoComplete="off"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          placeholder="PIN"
          aria-label="Guest code"
          className="w-24 rounded-sm border border-hairline bg-panel px-sm py-xs font-mono text-body text-ink"
        />
        <input
          type="date"
          value={expiry}
          onChange={(e) => setExpiry(e.target.value)}
          aria-label="Guest code expires (11:59 PM on this date)"
          title="Code turns off at 11:59 PM on this date"
          className="rounded-sm border border-hairline bg-panel px-sm py-xs font-body text-body text-ink"
        />
        <PulseButton
          variant="tonal"
          channel="effort"
          compact
          disabled={busy || nextSlot === null}
          onClick={add}
        >
          Add
        </PulseButton>
      </div>
      {msg && <div className="pl-[34px] font-body text-caption text-ink-dim">{msg}</div>}
    </div>
  );
}

/** One active guest with a revoke (clear code + delete the expiry automation). */
function GuestRow({ lockEntityId, slot, name, automationId }: { lockEntityId: string; slot: number; name: string; automationId: string }) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function revoke() {
    setBusy(true);
    setMsg(null);
    try {
      await call(clearUserCodeCall(slot), lockEntityId);
      await deleteAutomationConfig(automationId);
    } catch {
      setMsg("Couldn't revoke the guest.");
      setBusy(false);
    }
  }

  return (
    <div className="flex items-center gap-md px-lg py-md">
      <div className="min-w-0 flex-1">
        <div className="truncate font-body text-body text-ink">{name}</div>
        <div className="font-mono text-caption text-ink-faint">Slot {slot} · auto-expires</div>
      </div>
      <button
        type="button"
        aria-label={`Revoke ${name}`}
        disabled={busy}
        onClick={revoke}
        className="shrink-0 rounded-sm p-xs text-ink-dim transition-colors duration-fast hover:text-streak disabled:opacity-40"
      >
        <Trash2 size={18} />
      </button>
      {msg && <span className="font-body text-caption text-streak">{msg}</span>}
    </div>
  );
}

/**
 * Manage the physical keypad codes on a Z-Wave lock: named owner slots
 * (Christian / Elizabeth) plus time-limited guest codes whose expiry is enforced
 * by a Home Assistant automation. Shown on a lock's entity detail.
 */
export function LockCodes({ lockEntityId }: { lockEntityId: string }) {
  const automations = useAutomationEntities();

  // Active guests for THIS lock, resolved from the guest-expiry automations.
  const guests = useMemo(() => {
    const prefix = guestAutomationPrefixFor(lockEntityId);
    return automations
      .map((a) => {
        const id = String(a.attributes.id ?? "");
        if (!id.startsWith(prefix)) return null;
        const slot = guestSlotFromId(id);
        if (slot === null) return null;
        const alias = String(a.attributes.friendly_name ?? "");
        const name = alias.replace(/^Guest code expiry — /, "").replace(/ \(slot \d+\)$/, "") || `Slot ${slot}`;
        return { slot, name, automationId: id };
      })
      .filter((g): g is { slot: number; name: string; automationId: string } => g !== null)
      .sort((a, b) => a.slot - b.slot);
  }, [automations, lockEntityId]);

  const usedSlots = new Set(guests.map((g) => g.slot));
  let nextSlot: number | null = null;
  for (let s = FIRST_GUEST_SLOT; s <= LAST_GUEST_SLOT; s++) {
    if (!usedSlots.has(s)) {
      nextSlot = s;
      break;
    }
  }

  return (
    <PanelCard className="divide-y divide-hairline">
      {OWNER_SLOTS.map((s) => (
        <OwnerSlotRow key={s.slot} lockEntityId={lockEntityId} slot={s.slot} label={s.label} />
      ))}
      {guests.map((g) => (
        <GuestRow
          key={g.slot}
          lockEntityId={lockEntityId}
          slot={g.slot}
          name={g.name}
          automationId={g.automationId}
        />
      ))}
      <AddGuest lockEntityId={lockEntityId} nextSlot={nextSlot} />
    </PanelCard>
  );
}

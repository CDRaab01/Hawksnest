import { useState } from "react";
import { Lock, LockOpen, Loader } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { resolveName } from "../lib/resolve";
import type { CardProps } from "./types";

type Pending = "locking" | "unlocking" | null;

/**
 * Lock card. Locks are a physical-security exception to optimistic UI: we never
 * optimistically show "Unlocked". The action shows a pending state; in Phase 0
 * (no HA) we just settle to the requested state after a beat to demonstrate the
 * affordance. Green = secure (locked), orange = attention (unlocked).
 */
export function LockCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const [state, setState] = useState(entity.state);
  const [pending, setPending] = useState<Pending>(null);
  const locked = state === "locked";

  function request(target: "locked" | "unlocked") {
    setPending(target === "locked" ? "locking" : "unlocking");
    // Phase 0 stub for the reconcile-on-next-HA-event seam.
    window.setTimeout(() => {
      setState(target);
      setPending(null);
    }, 500);
  }

  const StatusIcon = pending ? Loader : locked ? Lock : LockOpen;
  const channel = locked ? "recovery" : "streak";
  const statusColor = pending
    ? "text-ink-dim"
    : locked
      ? "text-recovery"
      : "text-streak";
  const statusText = pending
    ? pending === "locking"
      ? "Locking…"
      : "Unlocking…"
    : locked
      ? "Locked"
      : "Unlocked";

  if (density === "compact") {
    return (
      <PanelCard tint={channel} className="p-md">
        <div className="flex items-center gap-md">
          <StatusIcon
            className={[statusColor, pending ? "animate-spin" : ""].join(" ")}
            size={22}
          />
          <div className="min-w-0">
            <div className="truncate font-body text-body text-ink">{name}</div>
            <div className={["text-caption", statusColor].join(" ")}>{statusText}</div>
          </div>
          <button
            type="button"
            aria-label={locked ? "Unlock" : "Lock"}
            disabled={!!pending}
            onClick={() => request(locked ? "unlocked" : "locked")}
            className="ml-auto rounded-sm border border-hairline p-sm text-ink-dim transition-transform duration-fast active:scale-95 disabled:opacity-40"
          >
            {locked ? <LockOpen size={18} /> : <Lock size={18} />}
          </button>
        </div>
      </PanelCard>
    );
  }

  return (
    <PanelCard tint={channel} className="p-lg">
      <div className="flex items-start gap-md">
        <StatusIcon
          className={[statusColor, pending ? "animate-spin" : ""].join(" ")}
          size={28}
        />
        <div className="min-w-0">
          <div className="font-display text-title text-ink">{name}</div>
          <div className={["font-body text-body", statusColor].join(" ")}>
            {statusText}
          </div>
        </div>
      </div>
      <div className="mt-lg grid grid-cols-2 gap-sm">
        <PulseButton
          variant="tonal"
          channel="recovery"
          active={locked}
          disabled={!!pending}
          onClick={() => request("locked")}
        >
          <Lock size={18} /> Lock
        </PulseButton>
        <PulseButton
          variant="tonal"
          channel="streak"
          active={!locked}
          disabled={!!pending}
          onClick={() => request("unlocked")}
        >
          <LockOpen size={18} /> Unlock
        </PulseButton>
      </div>
    </PanelCard>
  );
}

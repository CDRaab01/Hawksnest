import { useEffect, useState } from "react";
import { Lock, LockOpen, Loader } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

type Target = "locked" | "unlocked";

/**
 * Lock card. Locks are a physical-security exception to optimistic UI: we never
 * optimistically show "Unlocked". State is read from the store (entity prop); a
 * tap shows a pending spinner until HA reports the new state (or the action
 * fails). Green = secure (locked), orange = attention (unlocked).
 */
export function LockCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const locked = entity.state === "locked";
  const [pending, setPending] = useState<Target | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Clear the pending state once HA reports the requested state.
  useEffect(() => {
    if (pending && entity.state === pending) setPending(null);
  }, [entity.state, pending]);

  async function request(target: Target) {
    setError(null);
    setPending(target);
    try {
      await callService("lock", target === "locked" ? "lock" : "unlock", {
        entity_id: entity.entity_id,
      });
    } catch {
      setPending(null);
      setError("Couldn't reach the lock.");
    }
  }

  const StatusIcon = pending ? Loader : locked ? Lock : LockOpen;
  const channel = locked ? "recovery" : "streak";
  const statusColor = pending
    ? "text-ink-dim"
    : locked
      ? "text-recovery"
      : "text-streak";
  const statusText = pending
    ? pending === "locked"
      ? "Locking…"
      : "Unlocking…"
    : locked
      ? "Locked"
      : "Unlocked";

  const testId = `lock-card-${entity.entity_id}`;

  if (density === "compact") {
    return (
      <PanelCard tint={channel} className="p-md" testId={testId} dataState={entity.state}>
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
    <PanelCard tint={channel} className="p-lg" testId={testId} dataState={entity.state}>
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
          {error && (
            <div className="font-body text-caption text-streak">{error}</div>
          )}
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

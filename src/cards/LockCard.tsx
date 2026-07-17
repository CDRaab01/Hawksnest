import { useEffect, useRef, useState } from "react";
import { Lock, LockOpen, Loader, AlertTriangle } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { SlideToAct } from "../components/SlideToAct";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { Channel } from "../components/PanelCard";
import type { CardProps } from "./types";

type Target = "locked" | "unlocked";

/**
 * How long to keep spinning before concluding the lock isn't reporting back.
 * HA accepted the call but no state echo ever arrives when the Z-Wave node is
 * dead (or the deadbolt is out of range) while HA itself is fine — without this
 * the spinner would run forever. Comfortably longer than a healthy lock's throw.
 */
const LOCK_TIMEOUT_MS = 45_000;

/** How long the settle ("thunk") animation owns the icon + tint flash. */
const SETTLE_MS = 700;

/** One-shot settle-flash wash color: the NEW state's channel. */
const FLASH_COLOR: Record<"locked" | "unlocked", string> = {
  locked: "var(--recovery)",
  unlocked: "var(--streak)",
};

/**
 * Lock card. Locks are a physical-security exception to optimistic UI: we never
 * optimistically show "Unlocked". The control is a **slide-to-act** track (the
 * web port of Android's — the drag is the confirmation, so a stray tap on the
 * wall tablet can't unlock a door), and it holds an honest pending spinner until
 * HA reports the new state (or the action fails / times out). When the echo
 * lands, the bolt gets its moment: a small thunk settle on the icon and a
 * one-shot channel tint flash. Green = secure (locked), orange = attention.
 */
export function LockCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const locked = entity.state === "locked";
  // A jam is a terminal *failure* state HA reports when the bolt can't throw —
  // surface it explicitly. Never render a jammed lock as "Unlocked".
  const jammed = entity.state === "jammed";
  const [pending, setPending] = useState<Target | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Timestamp key of the last confirmed settle — keys/restarts the thunk.
  const [settled, setSettled] = useState(0);

  // Clear the pending spinner once HA settles: either it reached the requested
  // state (success — give the bolt its thunk), or it reported `jammed` (failure
  // — stop spinning and show it, rather than spin forever).
  useEffect(() => {
    if (!pending) return;
    if (entity.state === pending) {
      setPending(null);
      setSettled(Date.now());
      navigator.vibrate?.([15, 40, 25]);
    } else if (entity.state === "jammed") {
      setPending(null);
    }
  }, [entity.state, pending]);

  // The settle flash/thunk is one-shot; release it after the animation.
  useEffect(() => {
    if (!settled) return;
    const t = window.setTimeout(() => setSettled(0), SETTLE_MS);
    return () => window.clearTimeout(t);
  }, [settled]);

  // Safety net: HA can accept the call yet never echo a new state (dead Z-Wave
  // node, deadbolt out of range) — stop spinning after the timeout and say so,
  // rather than leaving a lock control that looks stuck forever.
  const timerRef = useRef<number | null>(null);
  useEffect(() => {
    if (!pending) {
      if (timerRef.current) window.clearTimeout(timerRef.current);
      return;
    }
    timerRef.current = window.setTimeout(() => {
      setPending(null);
      setError("The lock didn't respond.");
    }, LOCK_TIMEOUT_MS);
    return () => {
      if (timerRef.current) window.clearTimeout(timerRef.current);
    };
  }, [pending]);

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

  // The one action that makes sense from the current state (a jam retries lock).
  const target: Target = locked ? "unlocked" : "locked";
  const actionChannel: Channel = target === "unlocked" ? "streak" : "recovery";
  const slideLabel = locked
    ? "Slide to unlock"
    : jammed
      ? "Jammed — slide to retry"
      : "Slide to lock";
  const pendingLabel = (pending ?? target) === "unlocked" ? "Unlocking…" : "Locking…";

  const StatusIcon = pending ? Loader : jammed ? AlertTriangle : locked ? Lock : LockOpen;
  const channel = locked ? "recovery" : "streak";
  const statusColor = pending
    ? "text-ink-dim"
    : locked
      ? "text-recovery"
      : "text-streak";
  const statusText = pending
    ? pendingLabel
    : jammed
      ? "Jammed — try again"
      : locked
        ? "Locked"
        : "Unlocked";

  const testId = `lock-card-${entity.entity_id}`;
  const compact = density === "compact";

  return (
    <PanelCard
      tint={channel}
      className={["relative overflow-hidden", compact ? "p-md" : "p-lg"].join(" ")}
      testId={testId}
      dataState={entity.state}
    >
      {/* One-shot settle flash: the new state's channel washes the card and fades. */}
      {settled > 0 && (
        <div
          key={settled}
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 animate-settle-flash motion-reduce:hidden"
          style={{ background: FLASH_COLOR[locked ? "locked" : "unlocked"] }}
        />
      )}

      <div className="flex items-start gap-md">
        <span
          key={settled || "icon"}
          className={["inline-flex", settled ? "animate-thunk motion-reduce:animate-none" : ""].join(" ")}
        >
          <StatusIcon
            className={[statusColor, pending ? "animate-spin" : ""].join(" ")}
            size={compact ? 22 : 28}
          />
        </span>
        <div className="min-w-0">
          <div
            className={
              compact
                ? "truncate font-body text-body text-ink"
                : "font-display text-title text-ink"
            }
          >
            {name}
          </div>
          <div
            className={[compact ? "text-caption" : "font-body text-body", statusColor].join(" ")}
          >
            {statusText}
          </div>
          {error && (
            <div className="font-body text-caption text-streak">{error}</div>
          )}
        </div>
      </div>

      <div className={compact ? "mt-md" : "mt-lg"}>
        <SlideToAct
          label={slideLabel}
          pendingLabel={pendingLabel}
          icon={target === "unlocked" ? <LockOpen size={20} /> : <Lock size={20} />}
          channel={actionChannel}
          pending={pending !== null}
          disabled={entity.state === "unavailable"}
          onCommit={() => void request(target)}
          testId={`slide-${entity.entity_id}`}
        />
      </div>
    </PanelCard>
  );
}

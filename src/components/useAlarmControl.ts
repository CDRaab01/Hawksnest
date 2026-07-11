import { useEffect, useRef, useState } from "react";
import type { HassEntity } from "../lib/ha";
import type { ArmService } from "../lib/alarm";
import { callService } from "../store/connection";

/** The panel state each arm service is trying to reach (clears that button's pending). */
const TARGET_STATE: Record<ArmService, string> = {
  alarm_disarm: "disarmed",
  alarm_arm_home: "armed_home",
  alarm_arm_away: "armed_away",
};

/**
 * How long to wait for HA to reach the requested state before giving up. Longer
 * than the lock (arm/exit delays can run ~60s) and the timer resets on every
 * state change, so an active `arming` countdown never trips it — only true
 * silence does.
 */
const ALARM_TIMEOUT_MS = 90_000;

export interface AlarmControl {
  /** The arm service currently in flight (its button spins), or null. */
  pending: ArmService | null;
  /** A human-readable failure message, or null. */
  error: string | null;
  /** Fire an arm/disarm; non-optimistic — pending clears on HA's echo, not on tap. */
  arm: (service: ArmService) => void;
}

/**
 * Shared non-optimistic arm/disarm behaviour for the alarm surfaces (the
 * dashboard `SecurityStatusBar` and the `AlarmCard`): the tapped control shows a
 * pending spinner until HA reaches the requested state (or reports `triggered`),
 * a failed call surfaces an error instead of silently doing nothing, and a
 * safety-net timeout stops a spinner the panel never answers. Pass the live
 * alarm entity (or undefined when none is present).
 *
 * Mirrors the LockCard's pending contract — arm/disarm is security-critical, so
 * it is never optimistic. The Android `HomeViewModel`/`ControlGate` is the
 * Kotlin analogue.
 */
export function useAlarmControl(alarm: HassEntity | undefined): AlarmControl {
  const [pending, setPending] = useState<ArmService | null>(null);
  const [error, setError] = useState<string | null>(null);
  const state = alarm?.state;

  // Clear the spinner once HA settles: it reached the requested state, or it
  // fired (`triggered`). A transitional state (arming/pending/disarming) is HA
  // working, so keep the spinner up.
  useEffect(() => {
    if (!pending || state === undefined) return;
    if (state === TARGET_STATE[pending] || state === "triggered") setPending(null);
  }, [state, pending]);

  // Safety net: if the panel goes silent (never reaches the target, never even
  // transitions), stop spinning after the timeout. Resets on every state change
  // so a legitimate long exit-delay countdown keeps it alive.
  const timerRef = useRef<number | null>(null);
  useEffect(() => {
    if (!pending) {
      if (timerRef.current) window.clearTimeout(timerRef.current);
      return;
    }
    timerRef.current = window.setTimeout(() => {
      setPending(null);
      setError("The alarm panel didn't respond.");
    }, ALARM_TIMEOUT_MS);
    return () => {
      if (timerRef.current) window.clearTimeout(timerRef.current);
    };
  }, [pending, state]);

  function arm(service: ArmService) {
    if (!alarm) return;
    setError(null);
    setPending(service);
    void callService("alarm_control_panel", service, {
      entity_id: alarm.entity_id,
    }).catch(() => {
      setPending(null);
      setError("Couldn't reach the alarm panel.");
    });
  }

  return { pending, error, arm };
}

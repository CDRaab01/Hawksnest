import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  SnapshotBucketContext,
  type SnapshotBuckets,
} from "./snapshotBucketContext";

interface ProviderProps {
  children: ReactNode;
  intervalMs?: number;
  /** Test seam: fixes the session seed so snapshots of rendered URLs are stable. */
  seed?: number;
}

/**
 * Drives the snapshot buckets (see `snapshotBucketContext.ts`). One timer ticks
 * app-wide every `intervalMs` while the tab is visible, so camera tiles refresh on a
 * single beat instead of N independent timers (Ring rate-limits the proxy), and a
 * backgrounded tab stops polling entirely.
 *
 * Returning to the tab bumps `onOpen` only. Ring stays on the shared beat: its proxy
 * is metered and its battery cameras are pinned to a 300s snapshot interval, so an
 * extra fetch on open buys the same image twice.
 *
 * Both counters are SEEDED FROM THE CLOCK rather than starting at 0. Starting at 0
 * meant every cold load requested `..._=0` — byte-identical to the previous session's
 * first request — which the HTTP cache can and does serve from disk. The opening frame
 * could therefore be arbitrarily old rather than merely one beat stale. The seed only
 * has to DIFFER between sessions, not order them: it is a cache-buster, not a clock.
 * Increments stay monotonic within a session, so a backward clock jump mid-session
 * still cannot repeat a bucket.
 */
export function SnapshotBucketProvider({
  children,
  intervalMs = 10_000,
  seed,
}: ProviderProps) {
  const sessionSeed = useMemo(
    () => seed ?? Math.floor(Date.now() / 1000),
    [seed],
  );
  const [ticks, setTicks] = useState(0);
  const [opens, setOpens] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const start = () => {
      if (timer.current !== null) return;
      timer.current = setInterval(() => setTicks((t) => t + 1), intervalMs);
    };
    const stop = () => {
      if (timer.current === null) return;
      clearInterval(timer.current);
      timer.current = null;
    };
    const onVisibility = () => {
      if (document.hidden) {
        stop();
      } else {
        // Frigate refreshes immediately on return; Ring waits for the shared beat.
        setOpens((o) => o + 1);
        start();
      }
    };

    if (!document.hidden) start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      stop();
    };
  }, [intervalMs]);

  const value = useMemo<SnapshotBuckets>(
    () => ({
      shared: sessionSeed + ticks,
      onOpen: sessionSeed + ticks + opens,
    }),
    [sessionSeed, ticks, opens],
  );

  return (
    <SnapshotBucketContext.Provider value={value}>
      {children}
    </SnapshotBucketContext.Provider>
  );
}

import { useEffect, useRef, useState, type ReactNode } from "react";
import { SnapshotBucketContext } from "./snapshotBucketContext";

interface ProviderProps {
  children: ReactNode;
  intervalMs?: number;
}

/**
 * Drives the shared snapshot bucket (see `snapshotBucket.ts`). One timer ticks
 * app-wide every `intervalMs` while the tab is visible, so camera tiles refresh
 * on a single beat instead of N independent timers (Ring rate-limits the proxy),
 * and a backgrounded tab stops polling entirely.
 */
export function SnapshotBucketProvider({
  children,
  intervalMs = 10_000,
}: ProviderProps) {
  const [bucket, setBucket] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const start = () => {
      if (timer.current !== null) return;
      timer.current = setInterval(() => setBucket((b) => b + 1), intervalMs);
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
        // Refresh once on return, then resume ticking.
        setBucket((b) => b + 1);
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

  return (
    <SnapshotBucketContext.Provider value={bucket}>
      {children}
    </SnapshotBucketContext.Provider>
  );
}

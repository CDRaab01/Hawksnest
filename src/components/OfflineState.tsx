import { useCallback, useEffect, useState } from "react";
import { CloudOff } from "lucide-react";
import { PulseButton } from "./PulseButton";
import { useConnection, useHaBaseUrl } from "../store/entityStore";
import { retryConnection } from "../store/connection";
import { formatAsOf, probeHaReachability, type ReachabilityHint } from "../lib/offline";

const HINT_TEXT: Record<ReachabilityHint, string> = {
  "network-unreachable": "Your home network is unreachable — check Tailscale.",
  "ha-not-answering": "Home network is reachable — Home Assistant isn't answering.",
};

/**
 * The honest full-screen offline state, shown when Home Assistant can't be reached (terminal
 * error, or an in-session drop that outlived the 120s grace window). Deliberately renders NO
 * entity data — offline means "we don't know", never a stale snapshot. Carries the
 * last-connected readout, a Retry button (restarts the source, skipping the websocket lib's
 * internal backoff), and a passive reachability hint distinguishing "the network is down" from
 * "the server answers but HA doesn't". Mirrors Android's `ui/components/OfflineState.kt`.
 */
export function OfflineState() {
  const { status, error, lastConnectedAt } = useConnection();
  const baseUrl = useHaBaseUrl();
  const [hint, setHint] = useState<ReachabilityHint | null>(null);

  // One bounded probe per mount / retry — passive, never a polling loop.
  const probe = useCallback(() => {
    let alive = true;
    void probeHaReachability(baseUrl).then((h) => {
      if (alive) setHint(h);
    });
    return () => {
      alive = false;
    };
  }, [baseUrl]);
  useEffect(() => probe(), [probe]);

  return (
    <div
      data-testid="offline-state"
      className="flex flex-col items-center justify-center px-lg py-xxl text-center"
    >
      <span className="flex h-20 w-20 items-center justify-center rounded-full bg-streak-dim">
        <CloudOff className="text-streak" size={36} />
      </span>
      <h2 className="mt-lg font-display text-headline text-ink">
        Can&apos;t reach Home Assistant
      </h2>
      {status === "error" && error && (
        <p className="mt-xs font-body text-body text-ink-dim">{error}</p>
      )}
      {lastConnectedAt !== undefined && (
        <p className="mt-xs font-body text-body text-ink-dim">
          Last connected {formatAsOf(lastConnectedAt)}
        </p>
      )}
      {hint && (
        <p className="mt-xs font-body text-caption text-ink-dim" data-testid="offline-hint">
          {HINT_TEXT[hint]}
        </p>
      )}
      <div className="mt-lg">
        <PulseButton
          onClick={() => {
            setHint(null);
            probe();
            retryConnection();
          }}
        >
          Retry now
        </PulseButton>
      </div>
    </div>
  );
}

/**
 * The grace-window banner: an in-session drop keeps the last in-memory entities on screen —
 * dimmed, controls disabled — for at most 120s, and this persistent strip says exactly how old
 * they are. Lock/alarm state is excluded from the grace treatment entirely (masked to
 * `unavailable` the moment the socket drops — see `lib/offline.ts`).
 */
export function ReconnectingBanner({ asOf }: { asOf?: number }) {
  return (
    <div
      data-testid="reconnect-banner"
      className="flex items-center gap-sm rounded border border-hairline bg-streak-dim px-md py-sm"
    >
      <CloudOff className="shrink-0 text-streak" size={16} />
      <span className="font-body text-body text-streak">
        {asOf !== undefined ? `Reconnecting — as of ${formatAsOf(asOf)}` : "Reconnecting…"}
      </span>
    </div>
  );
}

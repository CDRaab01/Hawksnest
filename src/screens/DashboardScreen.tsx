import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { SecurityStatusBar } from "../components/SecurityStatusBar";
import { CameraWall } from "../components/CameraWall";
import { OfflineState, ReconnectingBanner } from "../components/OfflineState";
import { useConnection, useEntitiesByArea } from "../store/entityStore";
import { offlinePhase } from "../lib/offline";

/**
 * Dashboard — a glanceable, camera-forward landing screen (Ring-style). Security posture up top
 * (big arm circles + a one-line secure/at-risk summary), then the camera wall as the visual focus,
 * then a single compact "Rooms" entry. Device controls live one tap deeper, inside each room
 * (`/area/:area`) and the full room grid on `/rooms` — keeping this page uncluttered.
 *
 * Honest degraded offline handling (`lib/offline.ts`): after an in-session drop the last
 * in-memory entities stay on screen — dimmed, controls disabled, under a "Reconnecting — as of"
 * banner — for at most 120s (lock/alarm state is already masked at the store the moment the
 * socket drops). Beyond the window, or on a terminal error, this collapses to the full
 * OfflineState: no entity data at all, never a stale snapshot.
 */
export function DashboardScreen() {
  const { status, staleSince, lastConnectedAt } = useConnection();
  const areas = useEntitiesByArea();
  const preview = areas.map((a) => a.area).slice(0, 4).join(" · ");

  // 1s heartbeat while a drop is in progress so the grace window actually expires on screen.
  const [, setTick] = useState(0);
  const dropped = status === "error" || (status === "connecting" && staleSince !== undefined);
  useEffect(() => {
    if (!dropped) return;
    const t = window.setInterval(() => setTick((n) => n + 1), 1_000);
    return () => window.clearInterval(t);
  }, [dropped]);

  const phase = offlinePhase(status, staleSince, Date.now());
  if (phase === "offline") {
    return <OfflineState />;
  }

  return (
    <div className="space-y-xl">
      {phase === "grace" && <ReconnectingBanner asOf={lastConnectedAt} />}

      <div
        className={["space-y-xl", phase === "grace" ? "pointer-events-none opacity-50" : ""].join(" ")}
        aria-disabled={phase === "grace" || undefined}
      >
        <SecurityStatusBar />

        <CameraWall />

        {areas.length > 0 && (
          <section className="space-y-md">
            <SectionHeader label="Rooms" channel="recovery" />
            <Link to="/rooms">
              <PanelCard className="p-lg transition-transform duration-fast active:scale-[0.99]">
                <div className="flex items-center gap-md">
                  <div className="min-w-0">
                    <div className="font-display text-title text-ink">
                      {areas.length} {areas.length === 1 ? "room" : "rooms"}
                    </div>
                    <div className="truncate font-body text-body text-ink-dim">{preview}</div>
                  </div>
                  <ChevronRight className="ml-auto shrink-0 text-ink-faint" size={20} />
                </div>
              </PanelCard>
            </Link>
          </section>
        )}
      </div>
    </div>
  );
}

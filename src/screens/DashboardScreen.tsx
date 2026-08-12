import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { CardLink } from "../components/CardLink";
import { EntityCard } from "../components/EntityCard";
import { SecurityStatusBar } from "../components/SecurityStatusBar";
import { CameraWall } from "../components/CameraWall";
import { OfflineState, ReconnectingBanner } from "../components/OfflineState";
import { useConnection, useEntitiesByArea, useEntityStore } from "../store/entityStore";
import { useFavorites } from "../store/prefsStore";
import { overrides } from "../config/overrides";
import { cardDensityFor, isFeature } from "../lib/density";
import { offlinePhase } from "../lib/offline";
import type { HassEntity } from "../lib/ha";

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

  // Pinned favorites, in the user's stored order. Ids the store doesn't have are skipped —
  // `config/favorites.ts` seeds this before any entity has arrived, and a device can be renamed
  // or removed in HA long after it was pinned.
  const entities = useEntityStore((s) => s.entities);
  const favorites = useFavorites();
  const pinned = useMemo(
    () => favorites.map((id) => entities[id]).filter((e): e is HassEntity => Boolean(e)),
    [favorites, entities],
  );

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

        {/* Pinned — the user's own shortcuts, above the wall because that is what "pinned to
            Home" has always meant here. Three things promised this section and nothing rendered
            it: the Devices row's "Pin to dashboard" button, Customize's "pin it to Home", and
            `config/favorites.ts`'s own docstring. Pinning was a no-op you could perform, save and
            reorder. Like the room grid it is a shortcut, not a re-org — pinned entities still
            appear in their rooms. Absent entirely when nothing is pinned, so an unused feature
            costs no space. */}
        {pinned.length > 0 && (
          <section className="space-y-md">
            <SectionHeader label="Pinned" channel="strength" />
            <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
              {pinned.map((entity) => (
                <CardLink
                  key={entity.entity_id}
                  to={`/entity/${encodeURIComponent(entity.entity_id)}`}
                  className={isFeature(entity.entity_id) ? "sm:col-span-2" : ""}
                >
                  <EntityCard
                    entity={entity}
                    overrides={overrides}
                    density={cardDensityFor(entity.entity_id)}
                  />
                </CardLink>
              ))}
            </div>
          </section>
        )}

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

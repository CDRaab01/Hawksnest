import { useEffect, useMemo, useState } from "react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { Skeleton } from "../components/Skeleton";
import { EventTimeline } from "../components/history/EventTimeline";
import {
  HistoryFilterBar,
  type HistoryRange,
} from "../components/history/HistoryFilterBar";
import { fetchLogbook } from "../store/connection";
import { useConnection, useEntityCategories } from "../store/entityStore";
import { isPrimaryEntity } from "../lib/entityVisibility";
import type { LogEvent } from "../lib/logbook";

const RANGE_HOURS: Record<HistoryRange, number> = {
  "24h": 24,
  "7d": 24 * 7,
  "30d": 24 * 30,
};

// Float useful event domains to the front of the chip row.
const DOMAIN_ORDER = [
  "camera",
  "binary_sensor",
  "lock",
  "alarm_control_panel",
  "light",
];

function presentDomains(events: LogEvent[]): string[] {
  const seen = new Set<string>();
  for (const e of events) if (e.domain) seen.add(e.domain);
  return [...seen].sort((a, b) => {
    const ai = DOMAIN_ORDER.indexOf(a);
    const bi = DOMAIN_ORDER.indexOf(b);
    return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi) || a.localeCompare(b);
  });
}

/** Loading placeholder: a column of PULSE-toned timeline-row skeletons. */
function HistorySkeleton() {
  return (
    <PanelCard className="divide-y divide-hairline">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="flex items-center gap-md px-lg py-md">
          <Skeleton className="h-8 w-8 shrink-0 rounded-full" />
          <div className="min-w-0 flex-1 space-y-xs">
            <Skeleton className="h-3.5 rounded-sm" style={{ width: `${55 - i * 4}%` }} />
            <Skeleton className="h-3 w-24 rounded-sm" />
          </div>
          <Skeleton className="h-3 w-12 shrink-0 rounded-sm" />
        </div>
      ))}
    </PanelCard>
  );
}

/**
 * History hub — a unified, filterable event timeline for the whole home, built
 * on Home Assistant's logbook. Range + category filters narrow the feed; each
 * event links to its entity.
 */
export function HistoryScreen() {
  const { status } = useConnection();
  const categories = useEntityCategories();
  const [range, setRange] = useState<HistoryRange>("24h");
  const [domain, setDomain] = useState("all");
  const [events, setEvents] = useState<LogEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    const end = Date.now();
    const start = end - RANGE_HOURS[range] * 3_600_000;
    fetchLogbook(start, end)
      .then((evts) => {
        if (!cancelled) setEvents(evts);
      })
      .catch(() => {
        if (!cancelled) setError("Couldn't load history.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // Refetch on range change and once the source is live/ready.
  }, [range, status]);

  // Drop config/diagnostic + ring-mqtt housekeeping entities (the `sensor.*_last_activity`,
  // `*_info`, `*_battery`, … spam) so the timeline shows meaningful state changes, not noise.
  // `*_last_activity` is untagged by ring-mqtt, so a category-only check isn't enough.
  const meaningful = useMemo(
    () => events.filter((e) => !e.entityId || isPrimaryEntity(e.entityId, categories)),
    [events, categories],
  );
  const domains = useMemo(() => presentDomains(meaningful), [meaningful]);
  const shown = useMemo(
    () =>
      domain === "all"
        ? meaningful
        : meaningful.filter((e) => e.domain === domain),
    [meaningful, domain],
  );

  return (
    <div className="space-y-lg">
      <SectionHeader label="History" channel="effort" />
      <HistoryFilterBar
        range={range}
        onRange={setRange}
        domains={domains}
        domain={domain}
        onDomain={setDomain}
      />

      {error ? (
        <PanelCard className="p-xl text-center">
          <span className="font-body text-body text-streak">{error}</span>
        </PanelCard>
      ) : loading && events.length === 0 ? (
        <HistorySkeleton />
      ) : (
        <EventTimeline events={shown} />
      )}
    </div>
  );
}

import { Link, useParams } from "react-router-dom";
import { ChevronRight, ArrowLeft } from "lucide-react";
import { EntityCard } from "../components/EntityCard";
import { PanelCard } from "../components/PanelCard";
import { SectionHeader } from "../components/SectionHeader";
import type { Channel } from "../components/PanelCard";
import { overrides } from "../config/overrides";
import { resolveName } from "../lib/resolve";
import { entitiesByArea } from "../fixtures/entities";

// Repeating per-area channel, mirroring Spotter's dayChannel rotation.
const CHANNELS: Channel[] = ["streak", "effort", "strength", "recovery"];
const channelFor = (i: number) => CHANNELS[i % CHANNELS.length];

/**
 * Option C — "Area-first hub".
 * Top-level cards per Area drill into a detail view. Most app-like, best for many
 * entities. The Front Door area reproduces the Security scene on drill-in.
 */
export function OptionC() {
  const areas = entitiesByArea();

  return (
    <div className="space-y-xl">
      <SectionHeader label="Areas" channel="effort" />
      <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
        {areas.map((group, i) => {
          const channel = channelFor(i);
          const count = group.entities.length;
          const preview = [
            ...new Set(group.entities.map((e) => resolveName(e, overrides))),
          ]
            .slice(0, 3)
            .join(" · ");
          return (
            <Link key={group.area} to={`/c/${encodeURIComponent(group.area)}`}>
              <PanelCard tint={channel} className="p-lg">
                <div className="flex items-center gap-md">
                  <div className="min-w-0">
                    <div className="font-display text-title text-ink">
                      {group.area}
                    </div>
                    <div className="truncate font-body text-body text-ink-dim">
                      {count} {count === 1 ? "device" : "devices"} · {preview}
                    </div>
                  </div>
                  <ChevronRight className="ml-auto text-ink-faint" size={20} />
                </div>
              </PanelCard>
            </Link>
          );
        })}
      </div>
    </div>
  );
}

/** Drill-in detail view for a single area. */
export function OptionCArea() {
  const { area = "" } = useParams();
  const decoded = decodeURIComponent(area);
  const group = entitiesByArea().find((g) => g.area === decoded);

  return (
    <div className="space-y-md">
      <Link
        to="/c"
        className="inline-flex items-center gap-xs text-body text-ink-dim hover:text-ink"
      >
        <ArrowLeft size={16} /> Areas
      </Link>
      <SectionHeader label={decoded} channel="recovery" />
      {group ? (
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
          {group.entities.map((entity) => (
            <EntityCard
              key={entity.entity_id}
              entity={entity}
              overrides={overrides}
            />
          ))}
        </div>
      ) : (
        <PanelCard className="p-lg">
          <p className="font-body text-body text-ink-dim">Unknown area.</p>
        </PanelCard>
      )}
    </div>
  );
}

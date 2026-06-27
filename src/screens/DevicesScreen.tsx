import { useMemo, useState } from "react";
import { Battery, BatteryLow, Pin, Eye, EyeOff, AlertTriangle } from "lucide-react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { CardLink } from "../components/CardLink";
import { QuickControl } from "../components/devices/QuickControl";
import { overrides } from "../config/overrides";
import { resolveName, resolveIcon } from "../lib/resolve";
import { entityHealth } from "../lib/deviceHealth";
import { relativeTime } from "../lib/relativeTime";
import { groupByArea } from "../lib/areas";
import { isPrimaryEntity } from "../lib/entityVisibility";
import { NON_DEVICE_DOMAINS, domainOf } from "../lib/ha";
import type { HassEntity } from "../lib/ha";
import { useEntityStore, useEntityDevice } from "../store/entityStore";
import {
  usePrefsStore,
  useIsPinned,
  useIsHidden,
} from "../store/prefsStore";

function BatteryReadout({ pct }: { pct: number }) {
  const low = pct <= 20;
  const Icon = low ? BatteryLow : Battery;
  return (
    <span
      className={[
        "inline-flex items-center gap-xs font-mono text-caption font-semibold",
        low ? "text-streak" : "text-ink-dim",
      ].join(" ")}
    >
      <Icon size={14} />
      {pct}%
    </span>
  );
}

function DeviceRow({ entity }: { entity: HassEntity }) {
  const name = resolveName(entity, overrides);
  const Icon = resolveIcon(entity, overrides);
  const health = entityHealth(entity);
  const device = useEntityDevice(entity.entity_id);
  const pinned = useIsPinned(entity.entity_id);
  const hidden = useIsHidden(entity.entity_id);
  const togglePin = usePrefsStore((s) => s.togglePin);
  const toggleHidden = usePrefsStore((s) => s.toggleHidden);

  const meta = [
    health.online ? entity.state : "Offline",
    health.lastChangedMs ? relativeTime(health.lastChangedMs) : null,
    device?.model ?? device?.manufacturer ?? null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <CardLink to={`/entity/${encodeURIComponent(entity.entity_id)}`}>
      <PanelCard className={["p-md", hidden ? "opacity-50" : ""].join(" ")}>
        <div className="flex items-center gap-md">
          <span
            className={[
              "h-2 w-2 shrink-0 rounded-full",
              health.online ? "bg-recovery" : "bg-streak",
            ].join(" ")}
          />
          <Icon className="shrink-0 text-ink-dim" size={18} />
          <div className="min-w-0 flex-1">
            <div className="truncate font-body text-body-lg text-ink">{name}</div>
            <div className="truncate font-body text-caption text-ink-faint">
              {meta || entity.entity_id}
            </div>
          </div>

          {health.battery !== null && <BatteryReadout pct={health.battery} />}
          <QuickControl entity={entity} />

          <button
            type="button"
            aria-label={pinned ? "Unpin" : "Pin to dashboard"}
            onClick={() => togglePin(entity.entity_id)}
            className={[
              "rounded-sm p-xs transition-colors duration-fast",
              pinned ? "text-effort" : "text-ink-faint hover:text-ink",
            ].join(" ")}
          >
            <Pin size={16} />
          </button>
          <button
            type="button"
            aria-label={hidden ? "Unhide" : "Hide"}
            onClick={() => toggleHidden(entity.entity_id)}
            className="rounded-sm p-xs text-ink-faint transition-colors duration-fast hover:text-ink"
          >
            {hidden ? <EyeOff size={16} /> : <Eye size={16} />}
          </button>
        </div>
      </PanelCard>
    </CardLink>
  );
}

/**
 * Devices hub — the home's device-management console. A "Needs attention" rail
 * (offline / low battery) sits above the full, searchable device list grouped by
 * area, with inline quick controls, health read-outs, registry metadata, and
 * pin/hide organization folded in.
 */
export function DevicesScreen() {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntityStore((s) => s.areas);
  const categories = useEntityStore((s) => s.categories);
  const [query, setQuery] = useState("");

  // Hide HA config/diagnostic + ring-mqtt housekeeping entities (battery, last-activity, volume,
  // info, event-stream…) from the main list — they live under each device's detail view instead.
  // Also drop non-device domains (automations/scripts/scenes have their own surfaces; people/zones/
  // sun are infra).
  const all = useMemo(
    () =>
      Object.values(entities).filter(
        (e) =>
          isPrimaryEntity(e.entity_id, categories) &&
          !NON_DEVICE_DOMAINS.has(domainOf(e.entity_id)),
      ),
    [entities, categories],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return all;
    return all.filter((e) => {
      const name = resolveName(e, overrides).toLowerCase();
      const area = (areas[e.entity_id] ?? "").toLowerCase();
      return (
        name.includes(q) ||
        e.entity_id.toLowerCase().includes(q) ||
        area.includes(q)
      );
    });
  }, [all, areas, query]);

  const attention = useMemo(
    () => filtered.filter((e) => entityHealth(e).needsAttention),
    [filtered],
  );

  const groups = useMemo(
    () => groupByArea(filtered, areas, undefined, []),
    [filtered, areas],
  );

  return (
    <div className="space-y-xl">
      <div className="flex flex-wrap items-center gap-md">
        <SectionHeader label="Devices" channel="effort" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search devices, areas…"
          className="ml-auto w-full max-w-xs rounded-sm border border-hairline bg-panel px-md py-sm font-body text-body text-ink placeholder:text-ink-faint focus:border-hairline-strong focus:outline-none"
        />
      </div>

      {attention.length > 0 && (
        <section className="space-y-md">
          <SectionHeader
            label="Needs attention"
            channel="streak"
            trailing={
              <span className="inline-flex items-center gap-xs font-body text-caption text-streak">
                <AlertTriangle size={14} />
                {attention.length}
              </span>
            }
          />
          <div className="grid grid-cols-1 gap-sm lg:grid-cols-2">
            {attention.map((e) => (
              <DeviceRow key={e.entity_id} entity={e} />
            ))}
          </div>
        </section>
      )}

      <section className="space-y-lg">
        <SectionHeader
          label="All devices"
          channel="effort"
          trailing={
            <span className="font-body text-caption text-ink-faint">
              {filtered.length}
            </span>
          }
        />
        {groups.map((group, i) => (
          <div key={group.area} className="space-y-sm">
            <div className="font-body text-caption uppercase tracking-wide text-ink-faint">
              {group.area}
            </div>
            <div className="grid grid-cols-1 gap-sm lg:grid-cols-2">
              {group.entities.map((e) => (
                <DeviceRow key={e.entity_id} entity={e} />
              ))}
            </div>
            {i < groups.length - 1 && <div className="h-px" />}
          </div>
        ))}
        {filtered.length === 0 && (
          <PanelCard className="p-xl text-center">
            <span className="font-body text-body text-ink-dim">
              No devices match “{query}”.
            </span>
          </PanelCard>
        )}
      </section>
    </div>
  );
}

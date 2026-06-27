import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { EntityCard } from "../components/EntityCard";
import { PanelCard } from "../components/PanelCard";
import { SectionHeader } from "../components/SectionHeader";
import { Sparkline } from "../components/Sparkline";
import type { Channel } from "../components/PanelCard";
import { overrides } from "../config/overrides";
import { resolveIcon, resolveName } from "../lib/resolve";
import { domainOf } from "../lib/ha";
import { useEntity, useDeviceDiagnostics } from "../store/entityStore";
import { fetchHistory } from "../store/connection";
import type { HistoryPoint } from "../store/source";

/** Per-domain chart channel, mirroring each card's tint. */
const DOMAIN_CHANNEL: Record<string, Channel> = {
  lock: "recovery",
  cover: "recovery",
  alarm_control_panel: "recovery",
  light: "strength",
  climate: "strength",
  binary_sensor: "streak",
  fan: "effort",
  media_player: "effort",
};

const RANGES: { label: string; hours: number }[] = [
  { label: "6h", hours: 6 },
  { label: "24h", hours: 24 },
  { label: "7d", hours: 24 * 7 },
  { label: "30d", hours: 24 * 30 },
];

// Attributes that are plumbing, not worth surfacing in the detail list.
const HIDDEN_ATTRS = new Set([
  "friendly_name",
  "icon",
  "supported_features",
  "supported_color_modes",
  "device_class",
  "entity_picture",
]);

function prettyKey(key: string): string {
  return key
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/**
 * Entity detail (drill-in). Reachable by tapping any card. Shows the live
 * primary control (the same domain card), a relevant-attributes list, and a
 * state-history chart with a 6h / 24h / 7d / 30d range toggle backed by the active
 * source's fetchHistory (live HA over WS; synthesized in demo). Degrades to a
 * clear "history unavailable" state instead of crashing.
 */
export function EntityScreen() {
  const { id = "" } = useParams();
  const decoded = decodeURIComponent(id);
  const entity = useEntity(decoded);
  // Diagnostics for this device, filtered out of the main Devices list but kept reachable here.
  const diagnostics = useDeviceDiagnostics(decoded);

  const [hours, setHours] = useState(24);
  const [points, setPoints] = useState<HistoryPoint[]>([]);
  const [loading, setLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  // History only depends on which entity + range, NOT the live state object:
  // the live source rebuilds the entity map on every WS push, so depending on
  // `entity`'s identity would refetch history many times per second. Gate on
  // existence (a boolean) instead.
  const exists = Boolean(entity);
  useEffect(() => {
    if (!exists) return;
    let active = true;
    setLoading(true);
    setHistoryError(null);
    fetchHistory(decoded, hours)
      .then((p) => {
        if (active) setPoints(p);
      })
      .catch(() => {
        if (active) {
          setPoints([]);
          setHistoryError("History isn't available for this device.");
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [decoded, hours, exists]);

  const back = (
    <Link
      to="/"
      className="inline-flex items-center gap-xs text-body text-ink-dim transition-colors duration-fast hover:text-ink"
    >
      <ArrowLeft size={16} /> Home
    </Link>
  );

  if (!entity) {
    return (
      <div className="space-y-md">
        {back}
        <PanelCard className="p-lg">
          <p className="font-body text-body text-ink-dim">
            Device not found. It may be unavailable or hidden.
          </p>
        </PanelCard>
      </div>
    );
  }

  const Icon = resolveIcon(entity, overrides);
  const name = resolveName(entity, overrides);
  const channel = DOMAIN_CHANNEL[domainOf(decoded)] ?? "effort";
  const attrs = Object.entries(entity.attributes).filter(
    ([k, v]) =>
      !HIDDEN_ATTRS.has(k) &&
      (typeof v === "string" || typeof v === "number" || typeof v === "boolean"),
  );

  return (
    <div className="space-y-xl">
      {back}

      <div className="flex items-center gap-md">
        <Icon className="shrink-0 text-ink-dim" size={28} />
        <div className="min-w-0">
          <h1 className="truncate font-display text-headline text-ink">{name}</h1>
          <div className="font-mono text-caption text-ink-faint">{decoded}</div>
        </div>
      </div>

      <section className="space-y-md">
        <SectionHeader label="Control" channel={channel} />
        <EntityCard entity={entity} overrides={overrides} density="comfortable" />
      </section>

      <section className="space-y-md">
        <SectionHeader
          label="History"
          channel={channel}
          trailing={
            <div className="flex items-center gap-xs">
              {RANGES.map((r) => (
                <button
                  key={r.label}
                  type="button"
                  onClick={() => setHours(r.hours)}
                  aria-pressed={hours === r.hours}
                  className={[
                    "rounded-sm px-sm py-xs text-caption transition-colors duration-fast",
                    hours === r.hours
                      ? "bg-panel-high text-ink"
                      : "text-ink-dim hover:text-ink",
                  ].join(" ")}
                >
                  {r.label}
                </button>
              ))}
            </div>
          }
        />
        <PanelCard className="p-lg">
          {loading ? (
            <p className="font-body text-body text-ink-dim">Loading history…</p>
          ) : historyError ? (
            <p className="font-body text-body text-ink-dim">{historyError}</p>
          ) : points.length < 2 ? (
            <p className="font-body text-body text-ink-dim">
              Not enough history yet for this range.
            </p>
          ) : (
            <Sparkline points={points} channel={channel} height={96} />
          )}
        </PanelCard>
      </section>

      {diagnostics.length > 0 && (
        <section className="space-y-md">
          <SectionHeader label="Diagnostics" channel={channel} />
          <PanelCard className="divide-y divide-hairline">
            {diagnostics.map((d) => (
              <div
                key={d.entity_id}
                className="flex items-center justify-between gap-md px-lg py-md"
              >
                <span className="truncate font-body text-body text-ink-dim">
                  {resolveName(d, overrides)}
                </span>
                <span className="truncate font-body text-body text-ink">
                  {d.state}
                </span>
              </div>
            ))}
          </PanelCard>
        </section>
      )}

      {attrs.length > 0 && (
        <section className="space-y-md">
          <SectionHeader label="Attributes" channel={channel} />
          <PanelCard className="divide-y divide-hairline">
            {attrs.map(([k, v]) => (
              <div
                key={k}
                className="flex items-center justify-between gap-md px-lg py-md"
              >
                <span className="font-body text-body text-ink-dim">
                  {prettyKey(k)}
                </span>
                <span className="truncate font-body text-body text-ink">
                  {String(v)}
                </span>
              </div>
            ))}
          </PanelCard>
        </section>
      )}
    </div>
  );
}

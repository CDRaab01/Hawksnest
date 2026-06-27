export type HistoryRange = "24h" | "7d" | "30d";

// Recorder retention is 30 days (MariaDB `purge_keep_days: 30`), so a 30-day
// window is the deepest the backend can answer — anything older is purged.
const RANGE_LABEL: Record<HistoryRange, string> = {
  "24h": "Last 24h",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
};

const DOMAIN_LABEL: Record<string, string> = {
  lock: "Locks",
  binary_sensor: "Sensors",
  camera: "Cameras",
  alarm_control_panel: "Alarm",
  light: "Lights",
  switch: "Switches",
  cover: "Covers",
  climate: "Climate",
  media_player: "Media",
  fan: "Fans",
};

function domainLabel(domain: string): string {
  return DOMAIN_LABEL[domain] ?? domain.replace(/_/g, " ");
}

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={[
        "rounded-sm border px-md py-xs font-body text-caption transition-colors duration-fast",
        active
          ? "border-hairline-strong bg-panel-high text-ink"
          : "border-hairline text-ink-dim hover:text-ink",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

interface Props {
  range: HistoryRange;
  onRange: (r: HistoryRange) => void;
  domains: string[];
  domain: string;
  onDomain: (d: string) => void;
}

/** Range toggle + category chips for the History feed. */
export function HistoryFilterBar({
  range,
  onRange,
  domains,
  domain,
  onDomain,
}: Props) {
  return (
    <div className="flex flex-wrap items-center gap-sm">
      <div className="flex items-center gap-xs">
        {(["24h", "7d", "30d"] as const).map((r) => (
          <Chip key={r} active={range === r} onClick={() => onRange(r)}>
            {RANGE_LABEL[r]}
          </Chip>
        ))}
      </div>
      <span className="mx-xs h-4 w-px bg-hairline" />
      <div className="flex flex-wrap items-center gap-xs">
        <Chip active={domain === "all"} onClick={() => onDomain("all")}>
          All
        </Chip>
        {domains.map((d) => (
          <Chip key={d} active={domain === d} onClick={() => onDomain(d)}>
            {domainLabel(d)}
          </Chip>
        ))}
      </div>
    </div>
  );
}

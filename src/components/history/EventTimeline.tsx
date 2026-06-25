import { Link } from "react-router-dom";
import { resolveIcon } from "../../lib/resolve";
import { clockTime } from "../../lib/relativeTime";
import type { LogEvent } from "../../lib/logbook";
import { PanelCard } from "../PanelCard";

function dayLabel(ms: number): string {
  const d = new Date(ms);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return "Today";
  if (d.toDateString() === yesterday.toDateString()) return "Yesterday";
  return d.toLocaleDateString(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  });
}

function iconFor(ev: LogEvent) {
  return resolveIcon({
    entity_id: ev.entityId ?? `${ev.domain ?? "sensor"}.event`,
    state: ev.state ?? "",
    attributes: {},
  });
}

function groupByDay(events: LogEvent[]): Array<{ label: string; events: LogEvent[] }> {
  const out: Array<{ label: string; events: LogEvent[] }> = [];
  let currentKey = "";
  for (const ev of events) {
    const key = new Date(ev.when).toDateString();
    if (key !== currentKey) {
      out.push({ label: dayLabel(ev.when), events: [] });
      currentKey = key;
    }
    out[out.length - 1].events.push(ev);
  }
  return out;
}

function EventRow({ ev }: { ev: LogEvent }) {
  const Icon = iconFor(ev);
  const body = (
    <div className="flex items-center gap-md py-sm">
      <Icon className="shrink-0 text-ink-dim" size={18} />
      <div className="min-w-0 flex-1">
        <span className="font-body text-body text-ink">{ev.name}</span>{" "}
        <span className="font-body text-body text-ink-dim">{ev.message}</span>
      </div>
      <span className="shrink-0 font-mono text-caption text-ink-faint">
        {clockTime(ev.when)}
      </span>
    </div>
  );

  if (ev.entityId) {
    return (
      <Link
        to={`/entity/${encodeURIComponent(ev.entityId)}`}
        className="block rounded-sm px-sm transition-colors duration-fast hover:bg-panel-high"
      >
        {body}
      </Link>
    );
  }
  return <div className="px-sm">{body}</div>;
}

/** Day-grouped chronological event feed for the History hub. */
export function EventTimeline({ events }: { events: LogEvent[] }) {
  if (events.length === 0) {
    return (
      <PanelCard className="p-xl text-center">
        <span className="font-body text-body text-ink-dim">
          No events in this window.
        </span>
      </PanelCard>
    );
  }

  return (
    <div className="space-y-lg">
      {groupByDay(events).map((day) => (
        <section key={day.label} className="space-y-sm">
          <div className="font-body text-caption uppercase tracking-wide text-ink-faint">
            {day.label}
          </div>
          <PanelCard className="divide-y divide-hairline px-sm py-xs">
            {day.events.map((ev, i) => (
              <EventRow key={`${ev.entityId ?? ev.name}-${ev.when}-${i}`} ev={ev} />
            ))}
          </PanelCard>
        </section>
      ))}
    </div>
  );
}

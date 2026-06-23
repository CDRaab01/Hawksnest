import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { PanelCard } from "./PanelCard";
import type { Channel } from "./PanelCard";
import { overrides } from "../config/overrides";
import { resolveName } from "../lib/resolve";
import type { AreaGroup } from "../lib/areas";

// Repeating per-area channel, mirroring Spotter's dayChannel rotation.
const CHANNELS: Channel[] = ["streak", "effort", "strength", "recovery"];
const channelForIndex = (i: number) => CHANNELS[i % CHANNELS.length];

interface AreaCardProps {
  group: AreaGroup;
  index: number;
}

/** Area-hub card (C): name, device summary, channel tint → drills into detail. */
export function AreaCard({ group, index }: AreaCardProps) {
  const channel = channelForIndex(index);
  const count = group.entities.length;
  const preview = [
    ...new Set(group.entities.map((e) => resolveName(e, overrides))),
  ]
    .slice(0, 3)
    .join(" · ");

  return (
    <Link to={`/area/${encodeURIComponent(group.area)}`}>
      <PanelCard tint={channel} className="p-lg">
        <div className="flex items-center gap-md">
          <div className="min-w-0">
            <div className="font-display text-title text-ink">{group.area}</div>
            <div className="truncate font-body text-body text-ink-dim">
              {count} {count === 1 ? "device" : "devices"} · {preview}
            </div>
          </div>
          <ChevronRight className="ml-auto shrink-0 text-ink-faint" size={20} />
        </div>
      </PanelCard>
    </Link>
  );
}

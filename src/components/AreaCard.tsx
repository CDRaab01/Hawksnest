import { Link } from "react-router-dom";
import {
  Activity,
  Bath,
  Bed,
  Briefcase,
  Camera,
  ChevronRight,
  DoorClosed,
  DoorOpen,
  Layers,
  LayoutGrid,
  Lightbulb,
  LockOpen,
  Shield,
  Sofa,
  Thermometer,
  Trees,
  Utensils,
  UtensilsCrossed,
  Warehouse,
  WashingMachine,
  type LucideIcon,
} from "lucide-react";
import { PanelCard } from "./PanelCard";
import type { Channel } from "./PanelCard";
import { roomHighlights, roomIconKey, type RoomStat } from "../lib/rooms";
import { useEntityCategories } from "../store/entityStore";
import type { AreaGroup } from "../lib/areas";

// Repeating per-area channel, mirroring Spotter's dayChannel rotation.
const CHANNELS: Channel[] = ["streak", "effort", "strength", "recovery"];
const channelForIndex = (i: number) => CHANNELS[i % CHANNELS.length];

const CHANNEL_TEXT: Record<Channel, string> = {
  effort: "text-effort",
  strength: "text-strength",
  streak: "text-streak",
  recovery: "text-recovery",
};
// Pre-composited dim container fills (the design system's channel container tone), matching the
// Android `effortDim`/etc. tokens — solid, so text/icons on top stay predictable.
const CHANNEL_BG: Record<Channel, string> = {
  effort: "bg-effort-dim",
  strength: "bg-strength-dim",
  streak: "bg-streak-dim",
  recovery: "bg-recovery-dim",
};

const ROOM_ICON: Record<string, LucideIcon> = {
  kitchen: Utensils,
  dining: UtensilsCrossed,
  bath: Bath,
  bedroom: Bed,
  garage: Warehouse,
  office: Briefcase,
  living: Sofa,
  basement: Layers,
  laundry: WashingMachine,
  frontdoor: DoorOpen,
  outdoor: Trees,
  security: Shield,
  unassigned: LayoutGrid,
  default: DoorClosed,
};

const STAT_ICON: Record<RoomStat, LucideIcon> = {
  unlocked: LockOpen,
  motion: Activity,
  lights: Lightbulb,
  cameras: Camera,
  temperature: Thermometer,
};

interface AreaCardProps {
  group: AreaGroup;
  index: number;
}

/**
 * Area-hub card: a per-room icon in a tinted badge, a rotating channel accent, a device count, and a
 * few at-a-glance highlight chips (unlocked / motion / lights on / cameras / temp). Drills into the
 * area detail. Diagnostic entities are excluded so counts + highlights reflect real controls.
 */
/** States that read as "active" for the room summary (mirrors Devices v2). */
const ACTIVE_STATES = new Set([
  "on",
  "unlocked",
  "open",
  "opening",
  "playing",
  "heat",
  "cool",
]);

export function AreaCard({ group, index }: AreaCardProps) {
  const channel = channelForIndex(index);
  const categories = useEntityCategories();
  const entities = group.entities.filter((e) => !(e.entity_id in categories));
  const count = entities.length;
  const activeCount = entities.filter((e) => ACTIVE_STATES.has(e.state)).length;
  const highlights = roomHighlights(entities);
  const RoomIcon = ROOM_ICON[roomIconKey(group.area)] ?? ROOM_ICON.default;

  return (
    <Link
      to={`/area/${encodeURIComponent(group.area)}`}
      className="block transition-transform duration-fast ease-ease active:scale-[0.98]"
    >
      <PanelCard tint={channel} className="p-lg">
        <div className="flex items-center gap-md">
          <div
            className={[
              "flex h-11 w-11 shrink-0 items-center justify-center rounded-lg",
              CHANNEL_BG[channel],
            ].join(" ")}
          >
            <RoomIcon className={CHANNEL_TEXT[channel]} size={22} />
          </div>
          <div className="min-w-0 flex-1 space-y-xs">
            <div className="truncate font-display text-title text-ink">{group.area}</div>
            <div className="font-body text-caption text-ink-dim">
              {count} {count === 1 ? "device" : "devices"}
              {activeCount > 0 && (
                <span className={CHANNEL_TEXT[channel]}> · {activeCount} on</span>
              )}
            </div>
            {highlights.length > 0 && (
              <div className="flex flex-wrap gap-xs pt-xs">
                {highlights.map((h) => {
                  const StatIcon = STAT_ICON[h.stat];
                  return (
                    <span
                      key={h.stat}
                      className={[
                        "inline-flex items-center gap-xs rounded-md px-sm py-xs text-caption",
                        CHANNEL_BG[h.channel],
                        CHANNEL_TEXT[h.channel],
                      ].join(" ")}
                    >
                      <StatIcon size={13} />
                      {h.label}
                    </span>
                  );
                })}
              </div>
            )}
          </div>
          <ChevronRight className="shrink-0 text-ink-faint" size={20} />
        </div>
      </PanelCard>
    </Link>
  );
}

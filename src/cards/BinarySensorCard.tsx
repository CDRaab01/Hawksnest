import { CheckCircle2, DoorOpen, DoorClosed, AlertTriangle } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import type { Channel } from "../components/PanelCard";
import type { CardProps } from "./types";

/**
 * Read-only binary_sensor tile. Interprets state by device_class so a contact
 * reads "Open/Closed" and a safety/tamper sensor reads "Detected/Safe" — never
 * a raw "on"/"off". Open/unsafe = orange (attention); closed/safe = green.
 */
export function BinarySensorCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const on = entity.state === "on";
  const deviceClass = (entity.attributes.device_class as string | undefined) ?? "";

  const safety = ["safety", "tamper", "problem", "smoke", "gas", "moisture"].includes(
    deviceClass,
  );

  let text: string;
  let channel: Channel | "neutral";
  let Icon = on ? AlertTriangle : CheckCircle2;

  if (safety) {
    text = on ? "Detected" : "Safe";
    channel = on ? "streak" : "recovery";
  } else {
    // contact-style: door / window / opening / garage_door
    text = on ? "Open" : "Closed";
    channel = on ? "streak" : "neutral";
    Icon = on ? DoorOpen : DoorClosed;
  }

  const color =
    channel === "streak"
      ? "text-streak"
      : channel === "recovery"
        ? "text-recovery"
        : "text-ink-dim";

  return (
    <PanelCard className="p-lg">
      <div className="flex items-center gap-md">
        <Icon className={color} size={26} />
        <div className="min-w-0">
          <div className="truncate font-body text-body-lg text-ink">{name}</div>
          <div className={["font-body text-body", color].join(" ")}>{text}</div>
        </div>
      </div>
    </PanelCard>
  );
}

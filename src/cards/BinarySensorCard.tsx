import { CheckCircle2, DoorOpen, DoorClosed, AlertTriangle } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import type { CardProps } from "./types";

/**
 * Read-only binary_sensor tile. Interprets state by device_class so a contact
 * reads "Open/Closed" and a safety/tamper sensor reads "Detected/Safe" — never
 * a raw "on"/"off". Open/unsafe = orange (attention); closed/safe = green.
 */
export function BinarySensorCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const on = entity.state === "on";
  const deviceClass = (entity.attributes.device_class as string | undefined) ?? "";

  const safety = ["safety", "tamper", "problem", "smoke", "gas", "moisture"].includes(
    deviceClass,
  );

  let text: string;
  let Icon = on ? AlertTriangle : CheckCircle2;
  let color: string;

  if (safety) {
    text = on ? "Detected" : "Safe";
    color = on ? "text-streak" : "text-recovery";
  } else {
    // contact-style: door / window / opening / garage_door
    text = on ? "Open" : "Closed";
    color = on ? "text-streak" : "text-ink-dim";
    Icon = on ? DoorOpen : DoorClosed;
  }

  const compact = density === "compact";

  return (
    <PanelCard className={compact ? "p-md" : "p-lg"}>
      <div className="flex items-center gap-md">
        <Icon className={color} size={compact ? 22 : 26} />
        <div className="min-w-0">
          <div
            className={[
              "truncate font-body text-ink",
              compact ? "text-body" : "text-body-lg",
            ].join(" ")}
          >
            {name}
          </div>
          <div
            className={[compact ? "text-caption" : "text-body", "font-body", color].join(
              " ",
            )}
          >
            {text}
          </div>
        </div>
      </div>
    </PanelCard>
  );
}

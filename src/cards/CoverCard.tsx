import { useEffect, useState } from "react";
import { ArrowUp, ArrowDown, Square, Blinds } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { DataText } from "../components/DataText";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

type Action = "open" | "close" | "stop";

/**
 * Cover card (blinds / shades / garage). open/close/stop via cover.* services;
 * HA's echo reconciles the store. Closed = secure (recovery/green), anything
 * else = attention (streak/orange). Moving states show a transient label.
 * `current_position` (0–100) is surfaced as a mono readout when present.
 */
export function CoverCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const state = entity.state;
  const closed = state === "closed";
  const moving = state === "opening" || state === "closing";
  const position =
    typeof entity.attributes.current_position === "number"
      ? (entity.attributes.current_position as number)
      : undefined;

  const [pending, setPending] = useState<Action | null>(null);
  useEffect(() => {
    // Clear pending once HA settles into a resting state.
    if (pending && !moving) setPending(null);
  }, [moving, pending]);

  function request(action: Action) {
    setPending(action);
    void callService("cover", `${action}_cover`, {
      entity_id: entity.entity_id,
    }).catch(() => setPending(null));
  }

  const channel = closed ? "recovery" : "streak";
  const statusColor = closed ? "text-recovery" : "text-streak";
  const statusText = moving
    ? state === "opening"
      ? "Opening…"
      : "Closing…"
    : closed
      ? "Closed"
      : "Open";

  return (
    <PanelCard tint={channel} className="p-lg">
      <div className="flex items-start gap-md">
        <Blinds className={statusColor} size={28} />
        <div className="min-w-0">
          <div className="font-display text-title text-ink">{name}</div>
          <div className={["font-body text-body", statusColor].join(" ")}>
            {statusText}
            {position !== undefined && (
              <>
                {" · "}
                <DataText className={statusColor}>{position}</DataText>%
              </>
            )}
          </div>
        </div>
      </div>
      <div className="mt-lg grid grid-cols-3 gap-sm">
        <PulseButton
          variant="tonal"
          channel="streak"
          active={!closed && !moving}
          disabled={pending === "open"}
          onClick={() => request("open")}
        >
          <ArrowUp size={18} /> Open
        </PulseButton>
        <PulseButton
          variant="tonal"
          channel="effort"
          disabled={!moving}
          onClick={() => request("stop")}
        >
          <Square size={18} /> Stop
        </PulseButton>
        <PulseButton
          variant="tonal"
          channel="recovery"
          active={closed}
          disabled={pending === "close"}
          onClick={() => request("close")}
        >
          <ArrowDown size={18} /> Close
        </PulseButton>
      </div>
    </PanelCard>
  );
}

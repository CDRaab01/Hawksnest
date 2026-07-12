import { useEffect, useState } from "react";
import { ArrowUp, ArrowDown, Square, Blinds } from "lucide-react";

/**
 * The cover drawn at its actual position: a frame whose shade descends as
 * `position` falls (100 = fully open, 0 = fully closed — HA's convention).
 * The shade height transitions, so open/close movement reads on the glyph.
 */
function CoverGlyph({ position, className }: { position: number; className?: string }) {
  const shade = Math.max(0, Math.min(14, 14 * (1 - position / 100)));
  return (
    <svg viewBox="0 0 24 24" width={28} height={28} className={className} aria-hidden="true">
      <rect x="3" y="3" width="18" height="18" rx="2" fill="none" stroke="currentColor" strokeWidth="2" />
      <rect
        x="5.5"
        y="5.5"
        width="13"
        rx="1"
        fill="currentColor"
        opacity="0.55"
        style={{
          height: shade,
          transition: "height 400ms cubic-bezier(0.05, 0.7, 0.1, 1)",
        }}
      />
    </svg>
  );
}
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
        {position !== undefined ? (
          <CoverGlyph position={position} className={statusColor} />
        ) : (
          <Blinds className={statusColor} size={28} />
        )}
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

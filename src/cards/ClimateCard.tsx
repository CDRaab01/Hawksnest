import { Minus, Plus, Thermometer } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { DataText } from "../components/DataText";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

/**
 * Climate card. Shows the current temperature (mono) and an adjustable target
 * setpoint (±0.5°). The HA state is the hvac mode (heat/cool/auto/off); we tint
 * on the strength channel (magnitude/load) like Spotter's level readouts. The
 * setpoint write is climate.set_temperature; HA's echo reconciles the store.
 */
export function ClimateCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const attrs = entity.attributes;
  const current =
    typeof attrs.current_temperature === "number"
      ? (attrs.current_temperature as number)
      : undefined;
  const target =
    typeof attrs.temperature === "number"
      ? (attrs.temperature as number)
      : undefined;
  const unit = (attrs.unit_of_measurement as string) ?? "°";
  const step = typeof attrs.target_temp_step === "number"
    ? (attrs.target_temp_step as number)
    : 0.5;
  const off = entity.state === "off";

  function nudge(delta: number) {
    if (target === undefined) return;
    const next = Math.round((target + delta) * 10) / 10;
    void callService("climate", "set_temperature", {
      entity_id: entity.entity_id,
      temperature: next,
    });
  }

  return (
    <PanelCard tint={off ? undefined : "strength"} className="p-lg">
      <div className="flex items-center gap-md">
        <Thermometer className={off ? "text-ink-faint" : "text-strength"} size={26} />
        <div className="min-w-0">
          <div className="truncate font-body text-body-lg text-ink">{name}</div>
          <div className="font-body text-body text-ink-dim">
            {current !== undefined ? (
              <>
                Now <DataText className="text-ink">{current}</DataText>
                {unit} · {off ? "Off" : entity.state}
              </>
            ) : off ? (
              "Off"
            ) : (
              entity.state
            )}
          </div>
        </div>
      </div>
      {target !== undefined && (
        <div className="mt-lg flex items-center justify-between gap-md">
          <button
            type="button"
            aria-label="Lower target temperature"
            disabled={off}
            onClick={() => nudge(-step)}
            className="inline-flex h-10 w-10 items-center justify-center rounded-sm border border-hairline text-ink-dim transition-transform duration-fast active:scale-95 disabled:opacity-40 hover:text-ink"
          >
            <Minus size={20} />
          </button>
          <div className="text-center">
            <DataText size="md" className="text-strength">
              {target}
            </DataText>
            <span className="font-body text-body text-ink-dim">{unit}</span>
          </div>
          <button
            type="button"
            aria-label="Raise target temperature"
            disabled={off}
            onClick={() => nudge(step)}
            className="inline-flex h-10 w-10 items-center justify-center rounded-sm border border-hairline text-ink-dim transition-transform duration-fast active:scale-95 disabled:opacity-40 hover:text-ink"
          >
            <Plus size={20} />
          </button>
        </div>
      )}
    </PanelCard>
  );
}

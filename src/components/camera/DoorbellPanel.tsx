import { useEffect, useState } from "react";
import { useEntity } from "../../store/entityStore";
import { callService } from "../../store/connection";
import type { DoorbellControls } from "../../lib/doorbellControls";

/**
 * The doorbell's own settings: chime volume, whether the button makes a sound at the door,
 * the message played automatically to a visitor, and the siren.
 *
 * Everything renders only when its entity exists, so this panel serves whatever the model
 * actually exposes without asking which model it is — the same discipline as `PtzPanel`.
 * Only doorbells resolve controls at all (`doorbellControls.ts`), so a wall camera never
 * shows this.
 *
 * **The siren is the `siren` domain here**, not ring-mqtt's `switch.<base>_siren`. Its
 * service is `siren.turn_on`, which is why it is not routed through the existing
 * `SirenButton` — that component is the ring-mqtt path and calls `switch.turn_on`.
 */
export function DoorbellPanel({ controls }: { controls: DoorbellControls }) {
  return (
    <div className="flex flex-wrap items-start gap-lg rounded-lg bg-panel p-md">
      <div className="flex min-w-[12rem] flex-1 flex-col gap-md">
        <VolumeSlider entityId={controls.volume} />
        {controls.buttonSound && (
          <Toggle entityId={controls.buttonSound} label="Button sound" domain="switch" />
        )}
      </div>
      <div className="flex min-w-[12rem] flex-1 flex-col gap-md">
        {controls.autoReply && (
          <OptionSelect
            entityId={controls.autoReply}
            label="Auto reply"
            hint="Played to a visitor automatically."
          />
        )}
        {controls.playReply && (
          <OptionSelect entityId={controls.playReply} label="Play message" hint="Plays now." />
        )}
        {controls.siren && <SirenRow entityId={controls.siren} />}
      </div>
    </div>
  );
}

/**
 * Chime volume as a commit-on-release slider — same interaction as `PtzPanel`'s sliders.
 * The thumb follows the drag locally and the service call fires once on release, so
 * dragging across the range doesn't put twenty commands on the camera's control session.
 */
function VolumeSlider({ entityId }: { entityId: string }) {
  const entity = useEntity(entityId);
  const min = Number(entity?.attributes?.min ?? 0);
  const max = Number(entity?.attributes?.max ?? 100);
  const step = Number(entity?.attributes?.step ?? 1);
  const remote = Number(entity?.state);

  // null while not dragging, so the slider follows the camera — a change made in the
  // Reolink app shows here without fighting the local drag state.
  const [local, setLocal] = useState<number | null>(null);
  const value = local ?? (Number.isFinite(remote) ? remote : min);

  function commit(v: number) {
    setLocal(null);
    void callService("number", "set_value", { entity_id: entityId, value: v }).catch(() => {});
  }

  return (
    <label className="flex flex-col gap-xs">
      <span className="flex items-center justify-between caption-label text-ink-dim">
        Chime volume
        <span className="text-ink">{Number.isFinite(value) ? value : "—"}</span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        aria-label="Chime volume"
        onChange={(e) => setLocal(Number(e.target.value))}
        onPointerUp={(e) => commit(Number((e.target as HTMLInputElement).value))}
        onKeyUp={(e) => commit(Number((e.target as HTMLInputElement).value))}
        className="w-full accent-effort"
      />
    </label>
  );
}

/** A `switch` entity as a labelled toggle, matching `PtzPanel`'s autofocus control. */
function Toggle({
  entityId,
  label,
  domain,
}: {
  entityId: string;
  label: string;
  domain: string;
}) {
  const entity = useEntity(entityId);
  const on = entity?.state === "on";
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      onClick={() =>
        void callService(domain, on ? "turn_off" : "turn_on", { entity_id: entityId }).catch(
          () => {},
        )
      }
      className="flex items-center justify-between caption-label text-ink-dim"
    >
      {label}
      <span
        className={[
          "ml-sm h-5 w-9 rounded-full border border-hairline transition-colors duration-standard",
          on ? "bg-effort/80" : "bg-panel-high",
        ].join(" ")}
      >
        <span
          className={[
            "block h-3.5 w-3.5 rounded-full bg-white transition-transform duration-standard ease-ease",
            on ? "translate-x-[1.15rem]" : "translate-x-[0.15rem]",
          ].join(" ")}
          style={{ marginTop: "0.15rem" }}
        />
      </span>
    </button>
  );
}

/** A `select` entity. The options come from the camera itself. */
function OptionSelect({
  entityId,
  label,
  hint,
}: {
  entityId: string;
  label: string;
  hint: string;
}) {
  const entity = useEntity(entityId);
  const options: string[] = (entity?.attributes?.options as string[]) ?? [];
  if (options.length === 0) return null;

  return (
    <label className="flex flex-col gap-xs">
      <span className="caption-label text-ink-dim">{label}</span>
      <select
        value={entity?.state ?? ""}
        aria-label={label}
        onChange={(e) =>
          void callService("select", "select_option", {
            entity_id: entityId,
            option: e.target.value,
          }).catch(() => {})
        }
        className="rounded-sm bg-panel-high px-sm py-xs font-body text-body text-ink"
      >
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
      <span className="caption-label text-ink-faint">{hint}</span>
    </label>
  );
}

/**
 * The Reolink siren, on the `siren` domain.
 *
 * Two-tap to turn ON, one tap to turn OFF — the same asymmetry `SirenButton` uses, and for
 * the same reason: the siren is loud, so firing it should take deliberate intent while
 * silencing it should always be the fast path.
 */
function SirenRow({ entityId }: { entityId: string }) {
  const entity = useEntity(entityId);
  const on = entity?.state === "on";
  const [armed, setArmed] = useState(false);

  useEffect(() => {
    if (!armed) return;
    const t = setTimeout(() => setArmed(false), 3000);
    return () => clearTimeout(t);
  }, [armed]);

  function onClick() {
    if (on) {
      void callService("siren", "turn_off", { entity_id: entityId }).catch(() => {});
      setArmed(false);
      return;
    }
    if (!armed) {
      setArmed(true);
      return;
    }
    setArmed(false);
    void callService("siren", "turn_on", { entity_id: entityId }).catch(() => {});
  }

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={on ? "Silence the siren" : armed ? "Confirm siren" : "Sound the siren"}
      className={[
        "flex items-center justify-between rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
        on || armed ? "bg-streak-dim text-streak" : "bg-panel-high text-ink-dim hover:text-ink",
      ].join(" ")}
    >
      Siren
      <span>{on ? "Silence" : armed ? "Confirm" : "Off"}</span>
    </button>
  );
}

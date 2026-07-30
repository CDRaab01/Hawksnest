import { useEffect, useState } from "react";
import { useEntity } from "../../store/entityStore";
import { callService } from "../../store/connection";
import type { PtzControls } from "../../lib/cameraPtz";
import { PtzPad } from "./PtzPad";

/**
 * The camera-control drawer: movement pad, optical zoom, focus and saved
 * positions — the Reolink app's control surface, minus what this hardware or
 * this app deliberately doesn't do (no speed slider, no patrol, no digital zoom,
 * no auto-tracking; see `cameraPtz.ts` and the audit plan).
 *
 * Every control renders only when its entity exists, so the same panel serves an
 * E1 Zoom (pad + zoom + focus) and an E1 Pro (pad only) without asking what model
 * it is. Live only — moving the lens while watching recorded footage would change
 * what's being recorded with no visible feedback.
 */
export function PtzPanel({ ptz }: { ptz: PtzControls }) {
  return (
    <div className="flex flex-wrap items-start gap-lg rounded-lg bg-panel p-md">
      <PtzPad ptz={ptz} />
      <div className="flex min-w-[12rem] flex-1 flex-col gap-md">
        {ptz.zoom && <NumberSlider entityId={ptz.zoom} label="Zoom" />}
        {ptz.focus && (
          <FocusRow focusId={ptz.focus} autofocusId={ptz.autofocus} />
        )}
        {ptz.preset && <PresetSelect entityId={ptz.preset} />}
      </div>
    </div>
  );
}

/**
 * A `number` entity as a commit-on-release slider — the same interaction the
 * light dimmer uses. The thumb follows the drag locally and the service call
 * fires once on release, so dragging across the range doesn't put twenty
 * commands on the camera's control session.
 */
function NumberSlider({
  entityId,
  label,
  disabled = false,
}: {
  entityId: string;
  label: string;
  disabled?: boolean;
}) {
  const entity = useEntity(entityId);
  const min = Number(entity?.attributes?.min ?? 0);
  const max = Number(entity?.attributes?.max ?? 100);
  const step = Number(entity?.attributes?.step ?? 1);
  const remote = Number(entity?.state);

  const [local, setLocal] = useState<number | null>(null);
  // Follow the camera while not dragging: a preset recall or the Reolink app
  // moving the lens should be reflected here.
  useEffect(() => {
    setLocal(null);
  }, [remote]);
  const value = local ?? (Number.isFinite(remote) ? remote : min);

  const commit = () => {
    if (local === null) return;
    void callService("number", "set_value", {
      entity_id: entityId,
      value: local,
    }).catch(() => {});
  };

  return (
    <label className="flex flex-col gap-xs">
      <span className="flex items-center justify-between caption-label text-ink-dim">
        {label}
        <span className="text-ink-faint">{Math.round(value)}</span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled || !entity}
        aria-label={label}
        onChange={(e) => setLocal(Number(e.target.value))}
        onPointerUp={commit}
        onKeyUp={(e) => {
          if (e.key.startsWith("Arrow") || e.key === "Home" || e.key === "End") commit();
        }}
        className="w-full accent-effort disabled:opacity-40"
      />
    </label>
  );
}

/**
 * Focus, with its autofocus switch. The manual slider is disabled while
 * autofocus is on — the camera would immediately override anything set, so
 * offering it would be a control that visibly does nothing.
 */
function FocusRow({
  focusId,
  autofocusId,
}: {
  focusId: string;
  autofocusId: string | null;
}) {
  const auto = useEntity(autofocusId ?? "");
  const isAuto = auto?.state === "on";

  return (
    <div className="flex flex-col gap-xs">
      {autofocusId && (
        <button
          type="button"
          role="switch"
          aria-checked={isAuto}
          aria-label="Autofocus"
          onClick={() =>
            void callService("switch", isAuto ? "turn_off" : "turn_on", {
              entity_id: autofocusId,
            }).catch(() => {})
          }
          className="flex items-center justify-between caption-label text-ink-dim"
        >
          Autofocus
          <span
            className={[
              "ml-sm h-5 w-9 rounded-full border border-hairline transition-colors duration-standard",
              isAuto ? "bg-effort/80" : "bg-panel-high",
            ].join(" ")}
          >
            <span
              className={[
                "block h-3.5 w-3.5 rounded-full bg-white transition-transform duration-standard ease-ease",
                isAuto ? "translate-x-[1.15rem]" : "translate-x-[0.15rem]",
              ].join(" ")}
              style={{ marginTop: "0.15rem" }}
            />
          </span>
        </button>
      )}
      <NumberSlider entityId={focusId} label="Focus" disabled={isAuto} />
    </div>
  );
}

/** Saved camera positions. The options come from the camera itself. */
function PresetSelect({ entityId }: { entityId: string }) {
  const entity = useEntity(entityId);
  const options: string[] = (entity?.attributes?.options as string[]) ?? [];
  if (options.length === 0) return null;

  return (
    <label className="flex flex-col gap-xs">
      <span className="caption-label text-ink-dim">Position</span>
      <select
        value={entity?.state ?? ""}
        aria-label="Camera position preset"
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
    </label>
  );
}

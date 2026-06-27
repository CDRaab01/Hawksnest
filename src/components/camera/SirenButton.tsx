import { useEffect, useState } from "react";
import { Siren } from "lucide-react";
import { useEntity } from "../../store/entityStore";
import { callService } from "../../store/connection";

/**
 * Manual siren toggle for a Ring camera, bound to ring-mqtt's
 * `switch.<base>_siren` (only rendered when that entity exists — siren-capable
 * cameras only). The siren is loud, so turning it ON is a two-tap action: the
 * first tap arms the button ("Confirm"), a second within a few seconds fires it.
 * Turning it OFF is a single tap (you always want the fast path to silence it).
 */
export function SirenButton({ entityId }: { entityId: string }) {
  const entity = useEntity(entityId);
  const on = entity?.state === "on";
  const [armed, setArmed] = useState(false);

  // Drop the armed state if the user doesn't confirm in time.
  useEffect(() => {
    if (!armed) return;
    const t = setTimeout(() => setArmed(false), 3000);
    return () => clearTimeout(t);
  }, [armed]);

  function onClick() {
    if (on) {
      void callService("switch", "turn_off", { entity_id: entityId }).catch(() => {});
      setArmed(false);
    } else if (!armed) {
      setArmed(true);
    } else {
      void callService("switch", "turn_on", { entity_id: entityId }).catch(() => {});
      setArmed(false);
    }
  }

  const label = on ? "Siren on" : armed ? "Confirm" : "Siren";

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={on ? "Turn siren off" : armed ? "Confirm siren on" : "Sound siren"}
      aria-pressed={on}
      className={[
        "flex items-center gap-xs rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
        on
          ? "bg-streak text-streak-on"
          : armed
            ? "bg-streak-dim text-streak"
            : "bg-panel text-ink-dim hover:text-ink",
      ].join(" ")}
    >
      <Siren size={14} className={on ? "animate-pulse" : undefined} />
      {label}
    </button>
  );
}

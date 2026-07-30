import { useEntity } from "../store/entityStore";
import { callService } from "../store/connection";
import { PanelCard } from "./PanelCard";
import { SectionHeader } from "./SectionHeader";

/** Defined in the HA seed (`hawksnest-automation`), read by `hawksnest_push_camera_object`. */
export const HELPER_PERSON = "input_boolean.hawksnest_alert_person";
export const HELPER_PETS = "input_boolean.hawksnest_alert_pets";

/**
 * Household policy for camera object alerts — the `input_boolean`s the HA
 * automation gates on.
 *
 * Deliberately server-side rather than a device preference: flipping these stops
 * the push being *generated*, so it applies to every device and never wakes a
 * radio for an alert nobody wants. (Android's "Push alerts" switch is a different
 * thing — that one is just *this phone's* ntfy subscription.)
 *
 * Renders nothing — not even its section heading — when neither helper exists,
 * so an HA that predates them shows no empty card rather than a control that
 * silently does nothing.
 */
export function CameraAlertToggles() {
  const person = useEntity(HELPER_PERSON);
  const pets = useEntity(HELPER_PETS);
  if (!person && !pets) return null;

  return (
    <section className="space-y-md">
      <SectionHeader label="Notifications" channel="effort" />
      <PanelCard className="space-y-md p-lg">
        <div className="font-body text-body text-ink-dim">
          Camera alerts fire only while the alarm is armed, and apply to everyone's
          devices — not just this one.
        </div>
        {person && <HelperSwitch entityId={HELPER_PERSON} label="Person alerts" />}
        {pets && <HelperSwitch entityId={HELPER_PETS} label="Pet alerts" />}
      </PanelCard>
    </section>
  );
}

function HelperSwitch({ entityId, label }: { entityId: string; label: string }) {
  const entity = useEntity(entityId);
  const on = entity?.state === "on";

  return (
    <div className="flex items-center justify-between gap-md">
      <span className="font-body text-body-lg text-ink">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={label}
        onClick={() =>
          void callService("input_boolean", on ? "turn_off" : "turn_on", {
            entity_id: entityId,
          }).catch(() => {})
        }
        className={[
          "h-7 w-12 shrink-0 rounded-full border border-hairline transition-colors duration-standard",
          on ? "bg-effort/80" : "bg-panel-high",
        ].join(" ")}
      >
        <span
          className={[
            "block h-5 w-5 rounded-full bg-white transition-transform duration-standard ease-ease",
            on ? "translate-x-6" : "translate-x-1",
          ].join(" ")}
        />
      </button>
    </div>
  );
}

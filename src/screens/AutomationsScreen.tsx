import { useNavigate } from "react-router-dom";
import { Pencil, Play, Plus, Power, Workflow } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { SectionHeader } from "../components/SectionHeader";
import { useAutomationEntities, useConnection } from "../store/entityStore";
import { callService } from "../store/connection";
import { resolveName } from "../lib/resolve";
import type { HassEntity } from "../lib/ha";

/** "2m ago" style caption for last_triggered, or a gentle "never run" line. */
function lastTriggeredLabel(entity: HassEntity): string {
  const raw = entity.attributes.last_triggered;
  if (typeof raw !== "string" || !raw) return "Hasn't run yet";
  const when = new Date(raw);
  if (Number.isNaN(when.getTime())) return "Hasn't run yet";
  return `Last run ${when.toLocaleString()}`;
}

/** The HA Config API id lives on the automation entity's `id` attribute. */
function configId(entity: HassEntity): string | undefined {
  const id = entity.attributes.id;
  return typeof id === "string" ? id : undefined;
}

/** Small square icon button (mirrors the action buttons on the Customize screen). */
function IconButton({
  label,
  onClick,
  active = false,
  children,
}: {
  label: string;
  onClick: () => void;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={[
        "inline-flex h-9 w-9 items-center justify-center rounded-sm border transition-colors duration-fast",
        active
          ? "border-recovery/40 bg-recovery-dim text-recovery"
          : "border-hairline text-ink-dim hover:text-ink",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

function AutomationRow({ entity }: { entity: HassEntity }) {
  const navigate = useNavigate();
  const name = resolveName(entity);
  const enabled = entity.state === "on";
  const id = configId(entity);

  function toggle() {
    void callService("automation", enabled ? "turn_off" : "turn_on", {
      entity_id: entity.entity_id,
    });
  }
  function run() {
    void callService("automation", "trigger", { entity_id: entity.entity_id });
  }

  return (
    <div className="flex items-center gap-md px-lg py-md">
      <Workflow
        size={20}
        className={["shrink-0", enabled ? "text-effort" : "text-ink-faint"].join(" ")}
      />
      <div className="min-w-0">
        <div className="truncate font-body text-body-lg text-ink">{name}</div>
        <div className="font-body text-caption text-ink-faint">
          {enabled ? "Enabled" : "Disabled"} · {lastTriggeredLabel(entity)}
        </div>
      </div>
      <div className="ml-auto flex shrink-0 items-center gap-xs">
        <IconButton label="Run now" onClick={run}>
          <Play size={18} />
        </IconButton>
        <IconButton
          label={enabled ? "Disable" : "Enable"}
          active={enabled}
          onClick={toggle}
        >
          <Power size={18} />
        </IconButton>
        {id && (
          <IconButton
            label={`Edit ${name}`}
            onClick={() => navigate(`/automations/${encodeURIComponent(id)}`)}
          >
            <Pencil size={18} />
          </IconButton>
        )}
      </div>
    </div>
  );
}

/**
 * Automations — the list of service "linkages". Each row is a real Home
 * Assistant automation (surfaced as an `automation.*` entity); HA runs them,
 * Hawksnest just lists, toggles, runs, and links into the editor.
 */
export function AutomationsScreen() {
  const navigate = useNavigate();
  const automations = useAutomationEntities();
  const { status } = useConnection();

  return (
    <div className="space-y-xl">
      <section className="space-y-md">
        <SectionHeader
          label="Automations"
          channel="effort"
          trailing={
            <PulseButton compact onClick={() => navigate("/automations/new")}>
              <Plus size={16} /> New
            </PulseButton>
          }
        />

        {status === "demo" && (
          <p className="font-body text-caption text-ink-faint">
            Demo mode — automations are simulated locally. Connect Home Assistant
            in Settings to create ones that actually run.
          </p>
        )}

        {automations.length > 0 ? (
          <PanelCard className="divide-y divide-hairline">
            {automations.map((entity) => (
              <AutomationRow key={entity.entity_id} entity={entity} />
            ))}
          </PanelCard>
        ) : (
          <PanelCard className="p-lg">
            <p className="font-body text-body text-ink-dim">
              No automations yet. Create one to link your devices — e.g. "when the
              alarm is armed, lock every door" or "when motion is detected, turn on
              a light."
            </p>
          </PanelCard>
        )}
      </section>
    </div>
  );
}

import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, ExternalLink, Plus, Trash2 } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { SectionHeader } from "../components/SectionHeader";
import { overrides } from "../config/overrides";
import { resolveName } from "../lib/resolve";
import { groupByArea } from "../lib/areas";
import { primaryEntities } from "../lib/entityVisibility";
import { domainOf } from "../lib/ha";
import {
  ACTION_DOMAINS,
  DOMAIN_LABEL,
  PRESENCE_DOMAINS,
  PRESENCE_EVENTS,
  SUN_EVENTS,
  TRIGGER_TYPES,
  configToRule,
  newRule,
  newTriggerOfKind,
  ruleToConfig,
  stateOptionsFor,
  verbsFor,
  type Rule,
  type RuleAction,
  type RuleCondition,
  type RuleTrigger,
  type TriggerKind,
} from "../lib/automations";
import type { HassEntity } from "../lib/ha";
import { useEntityStore, usePresenceEntities } from "../store/entityStore";
import {
  deleteAutomationConfig,
  getAutomationConfig,
  saveAutomationConfig,
} from "../store/connection";
import { loadCredentials } from "../store/credentials";

const FIELD =
  "mt-xs w-full rounded-sm border border-hairline bg-bg px-md py-sm font-body text-body text-ink outline-none focus:border-hairline-strong";

/** Native entity picker, grouped by area; optionally filtered to one domain. */
function EntitySelect({
  value,
  onChange,
  ariaLabel,
  filterDomain,
  filterDomains,
}: {
  value: string;
  onChange: (id: string) => void;
  ariaLabel: string;
  filterDomain?: string;
  filterDomains?: string[];
}) {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntityStore((s) => s.areas);
  const categories = useEntityStore((s) => s.categories);
  // Drop HA config/diagnostic + ring-mqtt housekeeping entities so the picker lists real
  // controls/signals, not "Back Battery / Back Door Info / Back Event Stream / …" noise.
  const groups = useMemo(
    () => groupByArea(primaryEntities(Object.values(entities), categories), areas),
    [entities, areas, categories],
  );
  const allow = filterDomains ?? (filterDomain ? [filterDomain] : null);
  return (
    <select
      aria-label={ariaLabel}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={FIELD}
    >
      <option value="">Select a device…</option>
      {groups.map((group) => {
        const opts = allow
          ? group.entities.filter((e) => allow.includes(domainOf(e.entity_id)))
          : group.entities;
        if (opts.length === 0) return null;
        return (
          <optgroup key={group.area} label={group.area}>
            {opts.map((e) => (
              <option key={e.entity_id} value={e.entity_id}>
                {resolveName(e, overrides)}
              </option>
            ))}
          </optgroup>
        );
      })}
    </select>
  );
}

/** Curated state picker for a domain, or a free-text input when none is known. */
function StateInput({
  domain,
  value,
  onChange,
  ariaLabel,
}: {
  domain: string;
  value: string;
  onChange: (state: string) => void;
  ariaLabel: string;
}) {
  const options = stateOptionsFor(domain);
  if (options.length > 0) {
    return (
      <select
        aria-label={ariaLabel}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={FIELD}
      >
        <option value="">Pick a state…</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    );
  }
  return (
    <input
      aria-label={ariaLabel}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder="State, e.g. on"
      className={FIELD}
    />
  );
}

/** Per-type trigger fields — the "if this" half of the IFTTT-style builder. */
function TriggerFields({
  trigger,
  onChange,
  presenceEntities,
}: {
  trigger: RuleTrigger;
  onChange: (t: RuleTrigger) => void;
  presenceEntities: HassEntity[];
}) {
  switch (trigger.kind) {
    case "state":
      return (
        <>
          <label className="block">
            <span className="caption-label">Device</span>
            <EntitySelect
              ariaLabel="Trigger device"
              value={trigger.entityId}
              onChange={(entityId) => onChange({ kind: "state", entityId, to: "" })}
            />
          </label>
          <label className="block">
            <span className="caption-label">Reaches state</span>
            <StateInput
              ariaLabel="Trigger state"
              domain={trigger.entityId ? domainOf(trigger.entityId) : ""}
              value={trigger.to}
              onChange={(to) => onChange({ ...trigger, to })}
            />
          </label>
        </>
      );
    case "time":
      return (
        <label className="block">
          <span className="caption-label">At time</span>
          <input
            type="time"
            aria-label="Trigger time"
            value={trigger.at}
            onChange={(e) => onChange({ kind: "time", at: e.target.value })}
            className={FIELD}
          />
        </label>
      );
    case "sun":
      return (
        <div className="flex flex-wrap gap-md">
          <label className="min-w-0 flex-1">
            <span className="caption-label">Event</span>
            <select
              aria-label="Sun event"
              value={trigger.event}
              onChange={(e) =>
                onChange({ ...trigger, event: e.target.value as "sunrise" | "sunset" })
              }
              className={FIELD}
            >
              {SUN_EVENTS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="min-w-0 flex-1">
            <span className="caption-label">Offset (min, − before / + after)</span>
            <input
              type="number"
              aria-label="Sun offset minutes"
              value={trigger.offsetMinutes ?? 0}
              onChange={(e) =>
                onChange({ ...trigger, offsetMinutes: Math.trunc(Number(e.target.value)) || 0 })
              }
              className={FIELD}
            />
          </label>
        </div>
      );
    case "presence":
      return (
        <>
          <label className="block">
            <span className="caption-label">Person</span>
            <EntitySelect
              ariaLabel="Trigger person"
              value={trigger.personEntityId}
              onChange={(personEntityId) => onChange({ ...trigger, personEntityId })}
              filterDomains={PRESENCE_DOMAINS}
            />
            {presenceEntities.length === 0 && (
              <p className="mt-xs font-body text-caption text-ink-faint">
                No people or device trackers found in Home Assistant.
              </p>
            )}
          </label>
          <label className="block">
            <span className="caption-label">When they</span>
            <select
              aria-label="Presence event"
              value={trigger.event}
              onChange={(e) =>
                onChange({ ...trigger, event: e.target.value as "enter" | "leave" })
              }
              className={FIELD}
            >
              {PRESENCE_EVENTS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </>
      );
  }
}

/**
 * Automation editor — turns the friendly Rule form into a real HA automation
 * (`ruleToConfig`) and writes it via the Config API. Opening an existing one
 * that doesn't fit the V1 model shows a read-only "edit in Home Assistant"
 * escape hatch rather than a lossy form.
 */
export function AutomationEditScreen() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const isNew = !id || id === "new";

  // Live entity map (used for the per-action target lists).
  const allEntities = useEntityStore((s) => s.entities);
  const presenceEntities = usePresenceEntities();

  const [draft, setDraft] = useState<Rule>(() => newRule());
  const [load, setLoad] = useState<"loading" | "ready" | "unsupported" | "error">(
    isNew ? "ready" : "loading",
  );
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (isNew || !id) return;
    let cancelled = false;
    setLoad("loading");
    getAutomationConfig(id)
      .then((config) => {
        if (cancelled) return;
        if (!config) {
          setLoadError("That automation no longer exists.");
          setLoad("error");
          return;
        }
        const rule = configToRule(config);
        if (!rule) {
          setDraft((d) => ({ ...d, alias: String(config.alias ?? id) }));
          setLoad("unsupported");
          return;
        }
        setDraft(rule);
        setLoad("ready");
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : "Couldn't load automation.");
        setLoad("error");
      });
    return () => {
      cancelled = true;
    };
  }, [id, isNew]);

  // --- draft mutators -------------------------------------------------------
  const setTrigger = (trigger: RuleTrigger) => setDraft((d) => ({ ...d, trigger }));
  const setTriggerKind = (kind: TriggerKind) =>
    setDraft((d) => (d.trigger.kind === kind ? d : { ...d, trigger: newTriggerOfKind(kind) }));

  const addCondition = () =>
    setDraft((d) => ({
      ...d,
      conditions: [...d.conditions, { kind: "state", entityId: "", state: "" }],
    }));
  const updateCondition = (i: number, p: Partial<RuleCondition>) =>
    setDraft((d) => ({
      ...d,
      conditions: d.conditions.map((c, idx) => (idx === i ? { ...c, ...p } : c)),
    }));
  const removeCondition = (i: number) =>
    setDraft((d) => ({ ...d, conditions: d.conditions.filter((_, idx) => idx !== i) }));

  const addAction = () =>
    setDraft((d) => ({
      ...d,
      actions: [...d.actions, { domain: "lock", verb: "lock", targetEntityIds: [] }],
    }));
  const updateAction = (i: number, p: Partial<RuleAction>) =>
    setDraft((d) => ({
      ...d,
      actions: d.actions.map((a, idx) => (idx === i ? { ...a, ...p } : a)),
    }));
  const removeAction = (i: number) =>
    setDraft((d) => ({ ...d, actions: d.actions.filter((_, idx) => idx !== i) }));
  const setActionDomain = (i: number, domain: string) =>
    updateAction(i, {
      domain,
      verb: verbsFor(domain)[0]?.verb ?? "",
      targetEntityIds: [],
    });
  const toggleActionTarget = (i: number, entityId: string) =>
    setDraft((d) => ({
      ...d,
      actions: d.actions.map((a, idx) => {
        if (idx !== i) return a;
        const has = a.targetEntityIds.includes(entityId);
        return {
          ...a,
          targetEntityIds: has
            ? a.targetEntityIds.filter((x) => x !== entityId)
            : [...a.targetEntityIds, entityId],
        };
      }),
    }));

  // --- persistence ----------------------------------------------------------
  async function save() {
    setSaveError(null);
    if (!draft.alias.trim()) return setSaveError("Give the automation a name.");
    const t = draft.trigger;
    if (t.kind === "state" && (!t.entityId || !t.to)) {
      return setSaveError("Pick a trigger device and the state that should fire it.");
    }
    if (t.kind === "time" && !t.at) {
      return setSaveError("Pick the time the automation should fire.");
    }
    if (t.kind === "presence" && !t.personEntityId) {
      return setSaveError("Pick the person whose arrival or departure fires this.");
    }
    if (draft.actions.length === 0 || draft.actions.some((a) => a.targetEntityIds.length === 0)) {
      return setSaveError("Every action needs at least one target device.");
    }
    setBusy(true);
    try {
      await saveAutomationConfig(ruleToConfig({ ...draft, alias: draft.alias.trim() }));
      navigate("/automations");
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : "Couldn't save the automation.");
      setBusy(false);
    }
  }

  async function remove() {
    setSaveError(null);
    setBusy(true);
    try {
      await deleteAutomationConfig(draft.id);
      navigate("/automations");
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : "Couldn't delete the automation.");
      setBusy(false);
    }
  }

  const back = (
    <Link
      to="/automations"
      className="inline-flex items-center gap-xs text-body text-ink-dim transition-colors duration-fast hover:text-ink"
    >
      <ArrowLeft size={16} /> Automations
    </Link>
  );

  if (load === "loading") {
    return (
      <div className="space-y-xl">
        {back}
        <PanelCard className="p-lg">
          <p className="font-body text-body text-ink-dim">Loading…</p>
        </PanelCard>
      </div>
    );
  }

  if (load === "error") {
    return (
      <div className="space-y-xl">
        {back}
        <PanelCard className="p-lg" tint="streak">
          <p className="font-body text-body text-streak">{loadError}</p>
        </PanelCard>
      </div>
    );
  }

  if (load === "unsupported") {
    const haUrl = loadCredentials()?.url;
    return (
      <div className="space-y-xl">
        {back}
        <section className="space-y-md">
          <SectionHeader label="Edit in Home Assistant" channel="streak" />
          <PanelCard className="space-y-md p-lg">
            <div className="font-body text-body-lg text-ink">{draft.alias}</div>
            <p className="font-body text-body text-ink-dim">
              This automation uses features Hawksnest can't edit yet (for example
              multiple triggers, templates, or services it doesn't model). It still
              runs normally — edit it in Home Assistant to avoid losing detail.
            </p>
            {haUrl && id && (
              <a
                href={`${haUrl}/config/automation/edit/${encodeURIComponent(id)}`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-xs font-body text-body text-effort hover:underline"
              >
                Open in Home Assistant <ExternalLink size={16} />
              </a>
            )}
          </PanelCard>
        </section>
      </div>
    );
  }

  const triggerHint = TRIGGER_TYPES.find((t) => t.kind === draft.trigger.kind)?.hint;

  return (
    <div className="space-y-xl">
      {back}

      <section className="space-y-md">
        <SectionHeader label={isNew ? "New automation" : "Edit automation"} channel="effort" />
        <PanelCard className="space-y-md p-lg">
          <label className="block">
            <span className="caption-label">Name</span>
            <input
              aria-label="Name"
              value={draft.alias}
              onChange={(e) => setDraft((d) => ({ ...d, alias: e.target.value }))}
              placeholder="e.g. Lock all doors when armed"
              className={FIELD}
            />
          </label>
        </PanelCard>
      </section>

      {/* Trigger ------------------------------------------------------------ */}
      <section className="space-y-md">
        <SectionHeader label="When this happens" channel="effort" />
        <PanelCard className="space-y-md p-lg">
          <div>
            <span className="caption-label">Trigger type</span>
            <div className="mt-xs flex flex-wrap gap-xs">
              {TRIGGER_TYPES.map((tt) => {
                const active = draft.trigger.kind === tt.kind;
                return (
                  <button
                    key={tt.kind}
                    type="button"
                    aria-pressed={active}
                    aria-label={`Trigger type ${tt.label}`}
                    onClick={() => setTriggerKind(tt.kind)}
                    className={[
                      "rounded-sm border px-md py-sm font-body text-body transition-colors duration-fast",
                      active
                        ? "border-effort bg-effort/10 text-effort"
                        : "border-hairline text-ink-dim hover:text-ink",
                    ].join(" ")}
                  >
                    {tt.label}
                  </button>
                );
              })}
            </div>
            {triggerHint && (
              <p className="mt-xs font-body text-caption text-ink-faint">{triggerHint}</p>
            )}
          </div>

          <TriggerFields
            trigger={draft.trigger}
            onChange={setTrigger}
            presenceEntities={presenceEntities}
          />
        </PanelCard>
      </section>

      {/* Conditions (optional) --------------------------------------------- */}
      <section className="space-y-md">
        <SectionHeader
          label="Only if (optional)"
          channel="recovery"
          trailing={
            <PulseButton variant="ghost" compact onClick={addCondition}>
              <Plus size={16} /> Add
            </PulseButton>
          }
        />
        {draft.conditions.length === 0 ? (
          <PanelCard className="p-lg">
            <p className="font-body text-body text-ink-dim">
              No conditions — the actions run every time the trigger fires.
            </p>
          </PanelCard>
        ) : (
          draft.conditions.map((c, i) => (
            <PanelCard key={i} className="space-y-md p-lg">
              <div className="flex items-center gap-md">
                <label className="min-w-0 flex-1">
                  <span className="caption-label">Condition type</span>
                  <select
                    aria-label={`Condition ${i + 1} type`}
                    value={c.kind}
                    onChange={(e) =>
                      updateCondition(
                        i,
                        e.target.value === "timeWindow"
                          ? { kind: "timeWindow", entityId: undefined, state: undefined }
                          : { kind: "state", after: undefined, before: undefined },
                      )
                    }
                    className={FIELD}
                  >
                    <option value="state">A device is in a state</option>
                    <option value="timeWindow">Within a time window</option>
                  </select>
                </label>
                <button
                  type="button"
                  aria-label={`Remove condition ${i + 1}`}
                  onClick={() => removeCondition(i)}
                  className="mt-lg inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-sm border border-hairline text-ink-dim hover:text-streak"
                >
                  <Trash2 size={18} />
                </button>
              </div>

              {c.kind === "state" ? (
                <>
                  <label className="block">
                    <span className="caption-label">Device</span>
                    <EntitySelect
                      ariaLabel={`Condition ${i + 1} device`}
                      value={c.entityId ?? ""}
                      onChange={(entityId) => updateCondition(i, { entityId, state: "" })}
                    />
                  </label>
                  <label className="block">
                    <span className="caption-label">Is in state</span>
                    <StateInput
                      ariaLabel={`Condition ${i + 1} state`}
                      domain={c.entityId ? domainOf(c.entityId) : ""}
                      value={c.state ?? ""}
                      onChange={(state) => updateCondition(i, { state })}
                    />
                  </label>
                </>
              ) : (
                <div className="flex gap-md">
                  <label className="min-w-0 flex-1">
                    <span className="caption-label">After</span>
                    <input
                      type="time"
                      aria-label={`Condition ${i + 1} after`}
                      value={c.after ?? ""}
                      onChange={(e) => updateCondition(i, { after: e.target.value })}
                      className={FIELD}
                    />
                  </label>
                  <label className="min-w-0 flex-1">
                    <span className="caption-label">Before</span>
                    <input
                      type="time"
                      aria-label={`Condition ${i + 1} before`}
                      value={c.before ?? ""}
                      onChange={(e) => updateCondition(i, { before: e.target.value })}
                      className={FIELD}
                    />
                  </label>
                </div>
              )}
            </PanelCard>
          ))
        )}
      </section>

      {/* Actions ------------------------------------------------------------ */}
      <section className="space-y-md">
        <SectionHeader
          label="Do this"
          channel="strength"
          trailing={
            <PulseButton variant="ghost" compact onClick={addAction}>
              <Plus size={16} /> Add
            </PulseButton>
          }
        />
        {draft.actions.map((a, i) => {
          const targets = Object.values(allEntities).filter(
            (e) => domainOf(e.entity_id) === a.domain,
          );
          return (
            <PanelCard key={i} className="space-y-md p-lg">
              <div className="flex items-center gap-md">
                <label className="min-w-0 flex-1">
                  <span className="caption-label">Device type</span>
                  <select
                    aria-label={`Action ${i + 1} device type`}
                    value={a.domain}
                    onChange={(e) => setActionDomain(i, e.target.value)}
                    className={FIELD}
                  >
                    {ACTION_DOMAINS.map((d) => (
                      <option key={d} value={d}>
                        {DOMAIN_LABEL[d] ?? d}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="min-w-0 flex-1">
                  <span className="caption-label">Action</span>
                  <select
                    aria-label={`Action ${i + 1} verb`}
                    value={a.verb}
                    onChange={(e) => updateAction(i, { verb: e.target.value })}
                    className={FIELD}
                  >
                    {verbsFor(a.domain).map((v) => (
                      <option key={v.verb} value={v.verb}>
                        {v.label}
                      </option>
                    ))}
                  </select>
                </label>
                {draft.actions.length > 1 && (
                  <button
                    type="button"
                    aria-label={`Remove action ${i + 1}`}
                    onClick={() => removeAction(i)}
                    className="mt-lg inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-sm border border-hairline text-ink-dim hover:text-streak"
                  >
                    <Trash2 size={18} />
                  </button>
                )}
              </div>

              <div>
                <span className="caption-label">Target devices</span>
                {targets.length === 0 ? (
                  <p className="mt-xs font-body text-body text-ink-faint">
                    No {DOMAIN_LABEL[a.domain] ?? a.domain} devices found.
                  </p>
                ) : (
                  <div className="mt-xs space-y-xs">
                    {targets.map((e) => {
                      const checked = a.targetEntityIds.includes(e.entity_id);
                      return (
                        <label
                          key={e.entity_id}
                          className="flex items-center gap-sm font-body text-body text-ink"
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={() => toggleActionTarget(i, e.entity_id)}
                            aria-label={`${resolveName(e, overrides)} target for action ${i + 1}`}
                          />
                          {resolveName(e, overrides)}
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            </PanelCard>
          );
        })}
      </section>

      {/* Save / delete ------------------------------------------------------ */}
      {saveError && (
        <PanelCard className="p-lg" tint="streak">
          <p className="font-body text-body text-streak">{saveError}</p>
        </PanelCard>
      )}
      <div className="flex flex-wrap items-center gap-md">
        <PulseButton onClick={save} disabled={busy}>
          {isNew ? "Create automation" : "Save changes"}
        </PulseButton>
        {!isNew && (
          <PulseButton variant="ghost" onClick={remove} disabled={busy}>
            <Trash2 size={16} /> Delete
          </PulseButton>
        )}
      </div>
    </div>
  );
}

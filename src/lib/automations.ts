import { domainOf } from "./ha";

/**
 * Automations — Hawksnest's "linkages": user-built *if this, then that* rules
 * between Home Assistant services (e.g. "alarm armed-home → lock every door",
 * "motion detected → light on").
 *
 * Crucially, Hawksnest does NOT run these in the browser. It is only an editor:
 * a Rule is mapped to a real Home Assistant automation config (`ruleToConfig`)
 * and written via HA's Config API, so HA's own engine evaluates and runs it
 * 24/7 — even when no Hawksnest tab is open. This is the safe choice because the
 * rules control physical door locks.
 *
 * The Rule model is a deliberately small subset of HA automations (one state
 * trigger, optional state/time conditions, one+ service actions). Anything that
 * doesn't fit round-trips back as `null` from `configToRule`, so the editor can
 * fall back to "open in Home Assistant" rather than corrupting a hand-written
 * automation.
 */

/** A raw HA state string, e.g. "armed_home", "on", "locked". */
export type StateValue = string;

/** The single state trigger that fires the rule. */
export interface RuleTrigger {
  entityId: string;
  /** Fire when the entity reaches this state. */
  to: StateValue;
  /** Optional: only fire when coming *from* this state. */
  from?: StateValue;
  /** Optional debounce: state must hold for this many seconds (`for:`). */
  forSeconds?: number;
}

/** An optional guard. Either an entity-state check or a time-of-day window. */
export interface RuleCondition {
  kind: "state" | "timeWindow";
  /** state: entity must currently be `state`. */
  entityId?: string;
  state?: StateValue;
  /** timeWindow: only between `after` and `before` (HH:MM, 24h). */
  after?: string;
  before?: string;
}

/** A friendly verb applied to one or more target entities of a domain. */
export interface RuleAction {
  /** Target domain, e.g. "lock" | "light" | "alarm_control_panel". */
  domain: string;
  /** Verb token from {@link verbsFor}, e.g. "lock" | "turn_on". */
  verb: string;
  /** One OR many entities — many is what powers "lock ALL the doors". */
  targetEntityIds: string[];
  /** Optional extra service data, e.g. { brightness_pct: 60 }. */
  data?: Record<string, unknown>;
}

export interface Rule {
  /** HA automation id (generated for new rules; preserved on edit). */
  id: string;
  alias: string;
  trigger: RuleTrigger;
  conditions: RuleCondition[];
  actions: RuleAction[];
  mode?: "single" | "restart";
}

/**
 * A Home Assistant automation config as exchanged with the Config API. We keep
 * it loose (`[k: string]: unknown`) because HA may carry keys we don't model;
 * `configToRule` validates the subset we understand.
 */
export interface AutomationConfig {
  id: string;
  alias?: string;
  mode?: string;
  trigger?: unknown;
  condition?: unknown;
  action?: unknown;
  [k: string]: unknown;
}

// --- domain lookup tables (centralized; mirror the cards) -------------------

export interface StateOption {
  value: StateValue;
  label: string;
}

/**
 * Curated "to state" options per domain for the trigger/condition pickers.
 * Domains absent here fall back to a free-text state input. Mirrors the state
 * vocabulary the cards already use (AlarmCard, LockCard, BinarySensorCard).
 */
const STATE_OPTIONS: Record<string, StateOption[]> = {
  alarm_control_panel: [
    { value: "disarmed", label: "Disarmed" },
    { value: "armed_home", label: "Armed — Home" },
    { value: "armed_away", label: "Armed — Away" },
    { value: "armed_night", label: "Armed — Night" },
    { value: "triggered", label: "Triggered" },
  ],
  lock: [
    { value: "locked", label: "Locked" },
    { value: "unlocked", label: "Unlocked" },
  ],
  binary_sensor: [
    { value: "on", label: "Detected / On" },
    { value: "off", label: "Clear / Off" },
  ],
  light: [
    { value: "on", label: "On" },
    { value: "off", label: "Off" },
  ],
  switch: [
    { value: "on", label: "On" },
    { value: "off", label: "Off" },
  ],
  fan: [
    { value: "on", label: "On" },
    { value: "off", label: "Off" },
  ],
  cover: [
    { value: "open", label: "Open" },
    { value: "closed", label: "Closed" },
  ],
  person: [
    { value: "home", label: "Home" },
    { value: "not_home", label: "Away" },
  ],
  device_tracker: [
    { value: "home", label: "Home" },
    { value: "not_home", label: "Away" },
  ],
};

export interface VerbDef {
  /** Stable token stored on the action (reverse-looked-up on parse). */
  verb: string;
  label: string;
  /** Full HA service, e.g. "lock.lock". */
  service: string;
}

/**
 * Action verbs per domain → the HA service they call. Mirrors exactly what the
 * cards already invoke (lock.lock/unlock, light.turn_on/off, alarm arm/disarm…).
 */
const ACTION_VERBS: Record<string, VerbDef[]> = {
  lock: [
    { verb: "lock", label: "Lock", service: "lock.lock" },
    { verb: "unlock", label: "Unlock", service: "lock.unlock" },
  ],
  light: [
    { verb: "turn_on", label: "Turn on", service: "light.turn_on" },
    { verb: "turn_off", label: "Turn off", service: "light.turn_off" },
  ],
  switch: [
    { verb: "turn_on", label: "Turn on", service: "switch.turn_on" },
    { verb: "turn_off", label: "Turn off", service: "switch.turn_off" },
  ],
  fan: [
    { verb: "turn_on", label: "Turn on", service: "fan.turn_on" },
    { verb: "turn_off", label: "Turn off", service: "fan.turn_off" },
  ],
  cover: [
    { verb: "open", label: "Open", service: "cover.open_cover" },
    { verb: "close", label: "Close", service: "cover.close_cover" },
  ],
  alarm_control_panel: [
    { verb: "arm_home", label: "Arm — Home", service: "alarm_control_panel.alarm_arm_home" },
    { verb: "arm_away", label: "Arm — Away", service: "alarm_control_panel.alarm_arm_away" },
    { verb: "disarm", label: "Disarm", service: "alarm_control_panel.alarm_disarm" },
  ],
  scene: [{ verb: "activate", label: "Activate", service: "scene.turn_on" }],
  script: [{ verb: "run", label: "Run", service: "script.turn_on" }],
};

/** Friendly labels for the action-domain picker. */
export const DOMAIN_LABEL: Record<string, string> = {
  lock: "Locks",
  light: "Lights",
  switch: "Switches",
  fan: "Fans",
  cover: "Covers / Blinds",
  alarm_control_panel: "Alarm",
  scene: "Scenes",
  script: "Scripts",
};

/** Domains that can be targeted by an action, in picker order. */
export const ACTION_DOMAINS = Object.keys(ACTION_VERBS);

export function stateOptionsFor(domain: string): StateOption[] {
  return STATE_OPTIONS[domain] ?? [];
}

export function verbsFor(domain: string): VerbDef[] {
  return ACTION_VERBS[domain] ?? [];
}

export function serviceForVerb(domain: string, verb: string): string {
  const def = verbsFor(domain).find((v) => v.verb === verb);
  if (!def) throw new Error(`Unknown action ${domain}.${verb}`);
  return def.service;
}

/** Reverse of {@link serviceForVerb}: full service string → {domain, verb}. */
function verbForService(service: string): { domain: string; verb: string } | null {
  for (const [domain, verbs] of Object.entries(ACTION_VERBS)) {
    const match = verbs.find((v) => v.service === service);
    if (match) return { domain, verb: match.verb };
  }
  return null;
}

// --- factories --------------------------------------------------------------

/** A fresh id in HA's own scheme (epoch ms + jitter to avoid collisions). */
export function newRuleId(): string {
  const jitter = Math.floor(Math.random() * 1000)
    .toString()
    .padStart(3, "0");
  return `${Date.now()}${jitter}`;
}

/** `automation.<slug>` source for demo-mode synthetic entities. */
export function slugify(text: string): string {
  return (
    text
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "") || "automation"
  );
}

/** A blank draft for the "create" flow. */
export function newRule(): Rule {
  return {
    id: newRuleId(),
    alias: "",
    trigger: { entityId: "", to: "" },
    conditions: [],
    actions: [{ domain: "lock", verb: "lock", targetEntityIds: [] }],
    mode: "single",
  };
}

// --- Rule <-> HA config mapping ---------------------------------------------

function triggerToHa(t: RuleTrigger): Record<string, unknown> {
  const out: Record<string, unknown> = {
    platform: "state",
    entity_id: t.entityId,
    to: t.to,
  };
  if (t.from) out.from = t.from;
  if (t.forSeconds && t.forSeconds > 0) out.for = { seconds: t.forSeconds };
  return out;
}

function conditionToHa(c: RuleCondition): Record<string, unknown> {
  if (c.kind === "timeWindow") {
    const out: Record<string, unknown> = { condition: "time" };
    if (c.after) out.after = c.after;
    if (c.before) out.before = c.before;
    return out;
  }
  return { condition: "state", entity_id: c.entityId, state: c.state };
}

function actionToHa(a: RuleAction): Record<string, unknown> {
  const out: Record<string, unknown> = {
    service: serviceForVerb(a.domain, a.verb),
    target: { entity_id: a.targetEntityIds },
  };
  if (a.data && Object.keys(a.data).length > 0) out.data = a.data;
  return out;
}

/** Map a Rule to a Home Assistant automation config (Config API body). */
export function ruleToConfig(rule: Rule): AutomationConfig {
  return {
    id: rule.id,
    alias: rule.alias,
    mode: rule.mode ?? "single",
    trigger: [triggerToHa(rule.trigger)],
    condition: rule.conditions.map(conditionToHa),
    action: rule.actions.map(actionToHa),
  };
}

// --- best-effort parse back (returns null if outside the V1 subset) ---------

function asArray(v: unknown): unknown[] {
  if (Array.isArray(v)) return v;
  if (v === undefined || v === null) return [];
  return [v];
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function str(v: unknown): string | undefined {
  return typeof v === "string" ? v : undefined;
}

/** A single entity_id from a string or single-element array; else undefined. */
function singleEntityId(v: unknown): string | undefined {
  if (typeof v === "string") return v;
  if (Array.isArray(v) && v.length === 1 && typeof v[0] === "string") return v[0];
  return undefined;
}

function parseTrigger(raw: unknown): RuleTrigger | null {
  if (!isRecord(raw)) return null;
  // Accept classic `platform` and modern `trigger` keys.
  const platform = str(raw.platform) ?? str(raw.trigger);
  if (platform !== "state") return null;
  const entityId = singleEntityId(raw.entity_id);
  const to = str(raw.to);
  if (!entityId || !to) return null;
  const trigger: RuleTrigger = { entityId, to };
  const from = str(raw.from);
  if (from) trigger.from = from;
  if (isRecord(raw.for) && typeof raw.for.seconds === "number") {
    trigger.forSeconds = raw.for.seconds;
  }
  return trigger;
}

function parseCondition(raw: unknown): RuleCondition | null {
  if (!isRecord(raw)) return null;
  const kind = str(raw.condition);
  if (kind === "state") {
    const entityId = str(raw.entity_id);
    const state = str(raw.state);
    if (!entityId || state === undefined) return null;
    return { kind: "state", entityId, state };
  }
  if (kind === "time") {
    const after = str(raw.after);
    const before = str(raw.before);
    if (!after && !before) return null;
    return { kind: "timeWindow", after, before };
  }
  return null;
}

/** Target entity ids from `target.entity_id` or a bare `entity_id`. */
function collectTargets(raw: Record<string, unknown>): string[] {
  const fromTarget = isRecord(raw.target) ? raw.target.entity_id : undefined;
  const ids = fromTarget ?? raw.entity_id;
  if (typeof ids === "string") return [ids];
  if (Array.isArray(ids)) return ids.filter((x): x is string => typeof x === "string");
  return [];
}

function parseAction(raw: unknown): RuleAction | null {
  if (!isRecord(raw)) return null;
  // Accept classic `service` and modern `action` keys.
  const service = str(raw.service) ?? str(raw.action);
  if (!service) return null;
  const found = verbForService(service);
  if (!found) return null;
  const targets = collectTargets(raw);
  if (targets.length === 0) return null;
  const action: RuleAction = {
    domain: found.domain,
    verb: found.verb,
    targetEntityIds: targets,
  };
  if (isRecord(raw.data) && Object.keys(raw.data).length > 0) action.data = raw.data;
  return action;
}

/**
 * Parse an HA automation config into a Rule, or `null` if it falls outside the
 * V1 subset (multiple triggers, unsupported platforms/services, templates, …).
 * Callers treat `null` as "show read-only / edit in HA".
 */
export function configToRule(config: AutomationConfig): Rule | null {
  const triggers = asArray(config.trigger ?? config.triggers);
  if (triggers.length !== 1) return null;
  const trigger = parseTrigger(triggers[0]);
  if (!trigger) return null;

  const conditions: RuleCondition[] = [];
  for (const raw of asArray(config.condition ?? config.conditions)) {
    const parsed = parseCondition(raw);
    if (!parsed) return null;
    conditions.push(parsed);
  }

  const rawActions = asArray(config.action ?? config.actions);
  if (rawActions.length === 0) return null;
  const actions: RuleAction[] = [];
  for (const raw of rawActions) {
    const parsed = parseAction(raw);
    if (!parsed) return null;
    actions.push(parsed);
  }

  return {
    id: config.id,
    alias: str(config.alias) ?? config.id,
    trigger,
    conditions,
    actions,
    mode: config.mode === "restart" ? "restart" : "single",
  };
}

/** Domain of the trigger entity (for choosing its state options). */
export function triggerDomain(rule: Rule): string {
  return rule.trigger.entityId ? domainOf(rule.trigger.entityId) : "";
}

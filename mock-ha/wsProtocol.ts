/**
 * Mock Home Assistant WebSocket protocol — the stateful core, transport-agnostic
 * so it can be unit-tested with a fake transport (see __tests__/wsProtocol.test.ts)
 * and reused by both the Playwright E2E harness and (later) Android instrumented
 * tests. It speaks just enough of `home-assistant-js-websocket@9.6.0` to drive the
 * app's real `haSource` against scripted scenarios — never a real lock.
 *
 * Protocol facts this mirrors (verified against the lib's dist/):
 *  - The client sends `{type:"auth", access_token}` on open; we reply `auth_ok`
 *    (with `ha_version`) or `auth_invalid` + close.
 *  - For ha_version >= 2022.9 the client sends `supported_features` (id 1) and
 *    subscribes via `subscribe_entities` (compressed `a`/`c`/`r` diffs). We always
 *    echo `message.id` (ids are not fixed) and push state in the `a` (full-set)
 *    form, which the lib treats as "set entity state".
 *  - `call_service` carries the entity in `target.entity_id`; the resulting state
 *    echo over the entity subscription is what drives the non-optimistic LockCard
 *    pending -> confirmed transition. That echo is scriptable here (delay + outcome).
 */

// ---------------------------------------------------------------------------
// Data shapes
// ---------------------------------------------------------------------------

/** A full HA state object (what `get_states` returns; source of the compressed form). */
export interface HaStateObj {
  entity_id: string;
  state: string;
  attributes: Record<string, unknown>;
  last_changed?: string;
  last_updated?: string;
}

export interface AreaRegistryEntry {
  area_id: string;
  name: string;
}
export interface EntityRegistryEntry {
  entity_id: string;
  area_id: string | null;
  device_id: string | null;
  entity_category?: "config" | "diagnostic" | null;
  platform?: string | null;
}
export interface DeviceRegistryEntry {
  id: string;
  area_id: string | null;
  name?: string | null;
  name_by_user?: string | null;
  manufacturer?: string | null;
  model?: string | null;
  sw_version?: string | null;
}
export interface RegistryData {
  areas: AreaRegistryEntry[];
  entities: EntityRegistryEntry[];
  devices: DeviceRegistryEntry[];
}

/** One raw history sample (compressed form HA sends; epoch seconds). */
export interface RawHistorySample {
  s: string;
  lu: number;
}

/** How a scripted `call_service` resolves. */
export type Outcome = "confirm" | "jammed" | "reject" | "silent";

export interface ServiceOutcome {
  outcome: Outcome;
  /** Delay before the state echo lands (ms). Makes pending -> confirmed observable. */
  delayMs: number;
  /** For `confirm`, the state to echo (defaults derived from domain/service). */
  state?: string;
}

/** A recorded `call_service` (asserted by the round-trip specs). */
export interface ServiceCall {
  domain: string;
  service: string;
  service_data?: Record<string, unknown>;
  target?: { entity_id?: string | string[] };
}

/**
 * How a `camera/stream` request resolves. `ok` returns the mock HLS URL (after
 * an optional delay); `error` fails the command (HA can't produce a stream);
 * `timeout` never replies — the client's own 15s bound steps down, so specs
 * should prefer `error` for speed and keep `timeout` for protocol tests.
 */
export interface StreamOutcome {
  outcome: "ok" | "error" | "timeout";
  delayMs?: number;
}

export interface Scenario {
  haVersion: string;
  /** Expected token; null = accept any non-empty token. */
  token: string | null;
  /** Reject every auth attempt (drives the "Invalid access token." path). */
  rejectAuth?: boolean;
  entities: HaStateObj[];
  registries: RegistryData;
  history: Record<string, RawHistorySample[]>;
  logbook: unknown[];
  /** Per `domain.service` default outcomes for this scenario. */
  outcomes?: Record<string, ServiceOutcome>;
  /** Per entity_id `camera/stream` outcomes (`"default"` applies to the rest). */
  streamOutcomes?: Record<string, StreamOutcome>;
  defaultDelayMs?: number;
}

/** The wire abstraction one WS connection talks through. */
export interface Transport {
  send(msg: unknown): void;
  close(): void;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const nowIso = (): string => new Date().toISOString();
const toSecs = (iso?: string): number =>
  iso ? Math.floor(new Date(iso).getTime() / 1000) : Math.floor(Date.now() / 1000);

let ctxCounter = 0;
const nextContextId = (): string => `ctx-${++ctxCounter}`;

/** Default echoed state for a service when the scenario doesn't override it. */
export function defaultStateFor(domain: string, service: string): string | undefined {
  const key = `${domain}.${service}`;
  const map: Record<string, string> = {
    "lock.lock": "locked",
    "lock.unlock": "unlocked",
    "light.turn_on": "on",
    "light.turn_off": "off",
    "switch.turn_on": "on",
    "switch.turn_off": "off",
    "fan.turn_on": "on",
    "fan.turn_off": "off",
    "cover.open_cover": "open",
    "cover.close_cover": "closed",
    "alarm_control_panel.alarm_arm_away": "armed_away",
    "alarm_control_panel.alarm_arm_home": "armed_home",
    "alarm_control_panel.alarm_disarm": "disarmed",
  };
  if (map[key]) return map[key];
  if (service.endsWith("turn_on")) return "on";
  if (service.endsWith("turn_off")) return "off";
  return undefined;
}

function compress(e: HaStateObj): { s: string; a: Record<string, unknown>; c: string; lc: number; lu: number } {
  const lc = toSecs(e.last_changed);
  return { s: e.state, a: e.attributes, c: nextContextId(), lc, lu: toSecs(e.last_updated) || lc };
}

function addedEvent(list: HaStateObj[]): { a: Record<string, ReturnType<typeof compress>> } {
  const a: Record<string, ReturnType<typeof compress>> = {};
  for (const e of list) a[e.entity_id] = compress(e);
  return { a };
}

// ---------------------------------------------------------------------------
// Hub: single source of truth + broadcaster across all live sessions
// ---------------------------------------------------------------------------

export class MockHub {
  haVersion!: string;
  token!: string | null;
  rejectAuth = false;
  private entities = new Map<string, HaStateObj>();
  private registries!: RegistryData;
  private historyData!: Record<string, RawHistorySample[]>;
  private logbookData!: unknown[];
  private outcomes = new Map<string, ServiceOutcome>();
  private streamOutcomes = new Map<string, StreamOutcome>();
  private defaultDelayMs = 600;
  /** Echo timers in flight — cleared on load so a delayed echo from one test
   *  can't fire into the next test's freshly-reset scenario. */
  private pendingEchoes = new Set<ReturnType<typeof setTimeout>>();
  readonly calls: ServiceCall[] = [];
  /** Entity ids of every `camera/stream` request — lets retry tests assert the
   *  re-request happened without racing the (unplayable) mock HLS payload. */
  readonly streamRequests: string[] = [];
  /** Total sockets ever accepted — lets reconnect tests assert deterministically. */
  connections = 0;
  /** When true, new sockets are closed immediately (scripts a persistent outage so
   *  the app's grace-window / offline states can be asserted deterministically). */
  refuseConnections = false;
  private sessions = new Set<Session>();

  get sessionCount(): number {
    return this.sessions.size;
  }

  constructor(scenario: Scenario) {
    this.load(scenario);
  }

  /** Load a scenario as the new ground truth (does not touch live sessions). */
  load(scenario: Scenario): void {
    for (const t of this.pendingEchoes) clearTimeout(t);
    this.pendingEchoes.clear();
    this.haVersion = scenario.haVersion;
    this.token = scenario.token;
    this.rejectAuth = scenario.rejectAuth ?? false;
    this.entities = new Map(scenario.entities.map((e) => [e.entity_id, structuredClone(e)]));
    this.registries = structuredClone(scenario.registries);
    this.historyData = structuredClone(scenario.history);
    this.logbookData = structuredClone(scenario.logbook);
    this.outcomes = new Map(Object.entries(scenario.outcomes ?? {}));
    this.streamOutcomes = new Map(Object.entries(scenario.streamOutcomes ?? {}));
    this.defaultDelayMs = scenario.defaultDelayMs ?? 600;
    this.calls.length = 0;
    this.streamRequests.length = 0;
    this.refuseConnections = false;
  }

  /** Reset to a fresh scenario AND push the new full state to any live sessions. */
  reset(scenario: Scenario): void {
    this.load(scenario);
    const list = this.stateList();
    for (const s of this.sessions) s.pushEntities(list);
  }

  registerSession(s: Session): void {
    this.sessions.add(s);
    this.connections++;
  }
  unregisterSession(s: Session): void {
    this.sessions.delete(s);
  }

  stateList(): HaStateObj[] {
    return [...this.entities.values()];
  }
  registryData(): RegistryData {
    return this.registries;
  }
  history(): Record<string, RawHistorySample[]> {
    return this.historyData;
  }
  logbook(): unknown[] {
    return this.logbookData;
  }

  /** Control API: push an arbitrary state change over every live subscription. */
  pushState(input: { entity_id: string; state: string; attributes?: Record<string, unknown> }): void {
    const updated = this.setEntityState(input.entity_id, input.state, input.attributes);
    for (const s of this.sessions) s.pushEntities([updated]);
  }

  /** Control API: script how the next matching `call_service` resolves. */
  setServiceOutcome(input: {
    domain: string;
    service: string;
    entity_id?: string;
    outcome: Outcome;
    delayMs?: number;
    state?: string;
  }): void {
    this.outcomes.set(this.outcomeKey(input.domain, input.service, input.entity_id), {
      outcome: input.outcome,
      delayMs: input.delayMs ?? this.defaultDelayMs,
      state: input.state,
    });
  }

  /** Control API: script how `camera/stream` resolves for an entity (or `"default"` for all). */
  setStreamOutcome(input: {
    entity_id?: string;
    outcome: "ok" | "error" | "timeout";
    delayMs?: number;
  }): void {
    this.streamOutcomes.set(input.entity_id ?? "default", {
      outcome: input.outcome,
      delayMs: input.delayMs,
    });
  }

  streamOutcomeFor(entityId: string): StreamOutcome {
    return (
      this.streamOutcomes.get(entityId) ??
      this.streamOutcomes.get("default") ?? { outcome: "ok" }
    );
  }

  /** Control API: drop all live sockets (the app then auto-reconnects). */
  disconnectAll(): void {
    for (const s of [...this.sessions]) s.closeSocket();
  }

  recordCall(call: ServiceCall): void {
    this.calls.push(call);
  }

  outcomeFor(domain: string, service: string, entityId?: string): ServiceOutcome {
    return (
      this.outcomes.get(this.outcomeKey(domain, service, entityId)) ??
      this.outcomes.get(this.outcomeKey(domain, service)) ?? {
        outcome: "confirm",
        delayMs: this.defaultDelayMs,
      }
    );
  }

  /** Apply a service result after its delay and broadcast the echo. */
  scheduleServiceEcho(entityId: string, state: string, delayMs: number): void {
    const apply = () => {
      const updated = this.setEntityState(entityId, state);
      for (const s of this.sessions) s.pushEntities([updated]);
    };
    if (delayMs <= 0) {
      void Promise.resolve().then(apply);
    } else {
      const timer = setTimeout(() => {
        this.pendingEchoes.delete(timer);
        apply();
      }, delayMs);
      this.pendingEchoes.add(timer);
    }
  }

  private outcomeKey(domain: string, service: string, entityId?: string): string {
    return entityId ? `${domain}.${service}:${entityId}` : `${domain}.${service}`;
  }

  private setEntityState(
    entityId: string,
    state: string,
    attrs?: Record<string, unknown>,
  ): HaStateObj {
    const cur = this.entities.get(entityId);
    const updated: HaStateObj = {
      entity_id: entityId,
      state,
      attributes: attrs ? { ...(cur?.attributes ?? {}), ...attrs } : (cur?.attributes ?? {}),
      last_changed: nowIso(),
      last_updated: nowIso(),
    };
    this.entities.set(entityId, updated);
    return updated;
  }
}

// ---------------------------------------------------------------------------
// Session: one connection's protocol state machine
// ---------------------------------------------------------------------------

interface Msg {
  id?: number;
  type?: string;
  domain?: string;
  service?: string;
  service_data?: Record<string, unknown>;
  target?: { entity_id?: string | string[] };
  access_token?: string;
  event_type?: string;
  /** `camera/stream` carries the camera entity here. */
  entity_id?: string;
}

export class Session {
  private authed = false;
  private subId: number | null = null;
  private legacy = false;

  constructor(
    private hub: MockHub,
    private transport: Transport,
  ) {
    hub.registerSession(this);
    // Realistic (and harmless): announce auth_required; the lib sends `auth` anyway.
    this.transport.send({ type: "auth_required", ha_version: hub.haVersion });
  }

  handleMessage(raw: string | object): void {
    let msg: Msg;
    try {
      msg = typeof raw === "string" ? (JSON.parse(raw) as Msg) : (raw as Msg);
    } catch {
      return;
    }
    if (!this.authed) {
      this.handleAuth(msg);
      return;
    }
    this.dispatch(msg);
  }

  /** Push entity state to this session's active subscription (no-op if not subscribed). */
  pushEntities(list: HaStateObj[]): void {
    if (this.subId === null || list.length === 0) return;
    if (this.legacy) {
      for (const e of list) {
        this.transport.send({
          type: "event",
          id: this.subId,
          event: { event_type: "state_changed", data: { entity_id: e.entity_id, new_state: e } },
        });
      }
    } else {
      this.transport.send({ type: "event", id: this.subId, event: addedEvent(list) });
    }
  }

  closeSocket(): void {
    this.transport.close();
  }

  // --- internals ---------------------------------------------------------

  private handleAuth(msg: Msg): void {
    if (msg.type !== "auth") return;
    const token = msg.access_token ?? "";
    const ok =
      !this.hub.rejectAuth &&
      token.length > 0 &&
      (this.hub.token === null || token === this.hub.token);
    if (!ok) {
      this.transport.send({ type: "auth_invalid", message: "Invalid access token." });
      this.transport.close();
      return;
    }
    this.authed = true;
    this.transport.send({ type: "auth_ok", ha_version: this.hub.haVersion });
  }

  private result(id: number | undefined, result: unknown): void {
    this.transport.send({ type: "result", id, success: true, result });
  }

  private dispatch(msg: Msg): void {
    switch (msg.type) {
      case "supported_features":
        this.result(msg.id, null);
        break;
      case "subscribe_entities":
        this.subId = msg.id ?? null;
        this.legacy = false;
        this.result(msg.id, null);
        this.transport.send({ type: "event", id: msg.id, event: addedEvent(this.hub.stateList()) });
        break;
      case "subscribe_events":
        // Legacy path (ha_version < 2022.4): initial snapshot comes from get_states.
        if (msg.event_type === "state_changed") {
          this.subId = msg.id ?? null;
          this.legacy = true;
        }
        this.result(msg.id, null);
        break;
      case "get_states":
        this.result(msg.id, this.hub.stateList());
        break;
      case "config/area_registry/list":
        this.result(msg.id, this.hub.registryData().areas);
        break;
      case "config/entity_registry/list":
        this.result(msg.id, this.hub.registryData().entities);
        break;
      case "config/device_registry/list":
        this.result(msg.id, this.hub.registryData().devices);
        break;
      case "history/history_during_period":
        this.result(msg.id, this.hub.history());
        break;
      case "logbook/get_events":
        this.result(msg.id, this.hub.logbook());
        break;
      case "camera/stream":
        this.handleCameraStream(msg);
        break;
      case "call_service":
        this.handleCallService(msg);
        break;
      case "unsubscribe_events":
        this.result(msg.id, null);
        break;
      case "ping":
        this.transport.send({ type: "pong", id: msg.id });
        break;
      default:
        this.transport.send({
          type: "result",
          id: msg.id,
          success: false,
          error: { code: "unknown_command", message: `Unknown command ${msg.type ?? "?"}` },
        });
    }
  }

  /** `camera/stream` per the scripted outcome: ok (mock HLS URL, optionally
   *  delayed), error (HA can't produce a stream), or timeout (never reply —
   *  the client's own 15s bound steps down). */
  private handleCameraStream(msg: Msg): void {
    this.hub.streamRequests.push(msg.entity_id ?? "");
    const oc = this.hub.streamOutcomeFor(msg.entity_id ?? "");
    if (oc.outcome === "timeout") return;
    const reply = () => {
      if (oc.outcome === "error") {
        this.transport.send({
          type: "result",
          id: msg.id,
          success: false,
          error: { code: "start_stream_failed", message: "Timeout getting stream source" },
        });
      } else {
        this.result(msg.id, { url: "/api/hls/mock/master.m3u8" });
      }
    };
    if (oc.delayMs && oc.delayMs > 0) setTimeout(reply, oc.delayMs);
    else reply();
  }

  private handleCallService(msg: Msg): void {
    const domain = msg.domain ?? "";
    const service = msg.service ?? "";
    const target = msg.target;
    const rawTarget = target?.entity_id;
    const entityId = Array.isArray(rawTarget) ? rawTarget[0] : rawTarget;

    this.hub.recordCall({ domain, service, service_data: msg.service_data, target });

    const oc = this.hub.outcomeFor(domain, service, entityId);
    if (oc.outcome === "reject") {
      this.transport.send({
        type: "result",
        id: msg.id,
        success: false,
        error: { code: "service_failed", message: "Service call failed" },
      });
      return;
    }

    this.result(msg.id, { context: { id: nextContextId() } });
    if (oc.outcome === "silent") return;

    const newState = oc.outcome === "jammed" ? "jammed" : (oc.state ?? defaultStateFor(domain, service));
    if (!newState || !entityId) return;
    this.hub.scheduleServiceEcho(entityId, newState, oc.delayMs);
  }
}

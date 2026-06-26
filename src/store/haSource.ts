import {
  createConnection,
  createLongLivedTokenAuth,
  subscribeEntities,
  callService as haCallService,
  ERR_INVALID_AUTH,
  ERR_CANNOT_CONNECT,
  type Connection,
  type HassEntities,
} from "home-assistant-js-websocket";
import { useEntityStore } from "./entityStore";
import type { HistoryPoint, Source } from "./source";
import type { AutomationConfig } from "../lib/automations";
import { normalizeLogbook, type LogEvent, type RawLogbookEntry } from "../lib/logbook";
import {
  normalizeFrigateEvents,
  recordingUrlAt as buildRecordingUrl,
  eventClipUrl as buildEventClipUrl,
  FRIGATE_BASE,
  type CameraEvent,
  type RawFrigateEvent,
} from "../lib/cameraEvents";
import {
  buildAreaRegistry,
  buildDeviceIndex,
  toEntityRecord,
  type AreaRegistryEntry,
  type DeviceIndex,
  type DeviceRegistryEntry,
  type EntityRegistryEntry,
} from "./ha/registry";

export interface HaCredentials {
  url: string;
  token: string;
}

/** Injected so tests can drive the source with a fake connection. */
export interface HaSourceDeps {
  connect: (creds: HaCredentials) => Promise<Connection>;
  subscribe: (conn: Connection, cb: (e: HassEntities) => void) => () => void;
}

const defaultDeps: HaSourceDeps = {
  connect: (creds) =>
    createConnection({
      auth: createLongLivedTokenAuth(creds.url, creds.token),
    }),
  subscribe: (conn, cb) => subscribeEntities(conn, cb),
};

/**
 * Pull the three registries once and resolve BOTH the entity → area-name map and
 * the richer device index (manufacturer/model/firmware + entity ownership) the
 * Devices hub needs.
 */
async function fetchRegistry(
  conn: Connection,
): Promise<{ areas: ReturnType<typeof buildAreaRegistry>; devices: DeviceIndex }> {
  const [areas, entities, devices] = await Promise.all([
    conn.sendMessagePromise<AreaRegistryEntry[]>({
      type: "config/area_registry/list",
    }),
    conn.sendMessagePromise<EntityRegistryEntry[]>({
      type: "config/entity_registry/list",
    }),
    conn.sendMessagePromise<DeviceRegistryEntry[]>({
      type: "config/device_registry/list",
    }),
  ]);
  return {
    areas: buildAreaRegistry(areas, entities, devices),
    devices: buildDeviceIndex(areas, entities, devices),
  };
}

/**
 * Ask HA for the logbook over `[startMs, endMs]`. Optionally narrowed to
 * specific entities. Returns normalized, newest-first events.
 */
async function fetchLogbook(
  conn: Connection,
  startMs: number,
  endMs: number,
  entityIds?: string[],
): Promise<LogEvent[]> {
  const msg = {
    type: "logbook/get_events",
    start_time: new Date(startMs).toISOString(),
    end_time: new Date(endMs).toISOString(),
    ...(entityIds && entityIds.length > 0 ? { entity_ids: entityIds } : {}),
  };
  const raw = await conn.sendMessagePromise<RawLogbookEntry[]>(msg);
  return normalizeLogbook(raw ?? []);
}

function describeError(err: unknown): string {
  if (err === ERR_INVALID_AUTH) return "Invalid access token.";
  if (err === ERR_CANNOT_CONNECT) return "Can't reach Home Assistant at that URL.";
  return "Connection to Home Assistant failed.";
}

/** Human message for a failed Config API call (writing automations needs admin). */
function describeConfigError(status: number): string {
  if (status === 401 || status === 403) {
    return "Your Home Assistant token can't edit automations (it needs an admin user).";
  }
  return `Home Assistant rejected the automation (${status}).`;
}

/**
 * Home Assistant's automation Config API. Hawksnest is served same-origin with
 * HA (the nginx pod reverse-proxies `/api`), so these authenticated REST calls
 * reuse the long-lived token with no CORS. HA reloads automations after each
 * write, and the changed `automation.*` entity flows back over the live entity
 * subscription — no manual refresh.
 */
function automationUrl(creds: HaCredentials, id: string): string {
  return `${creds.url}/api/config/automation/config/${encodeURIComponent(id)}`;
}

/** One raw sample from `history/history_during_period` (compressed or legacy). */
interface RawHistoryState {
  s?: string;
  state?: string;
  lu?: number;
  lc?: number;
  last_updated?: string | number;
  last_changed?: string | number;
}

/** Epoch ms from a sample's last_updated/last_changed (HA sends seconds). */
function sampleTime(p: RawHistoryState): number {
  const secs = p.lu ?? p.lc;
  if (typeof secs === "number") return secs * 1000;
  const legacy = p.last_updated ?? p.last_changed;
  if (typeof legacy === "number") return legacy * 1000;
  if (typeof legacy === "string") return new Date(legacy).getTime();
  return Date.now();
}

/**
 * Ask HA for one entity's history over the WebSocket. `minimal_response` +
 * `no_attributes` keep the payload small (we only chart state). HA keys the
 * response by entity_id; missing entity ⇒ empty series.
 */
async function fetchEntityHistory(
  conn: Connection,
  entityId: string,
  hours: number,
): Promise<HistoryPoint[]> {
  const start = new Date(Date.now() - hours * 3600_000).toISOString();
  const result = await conn.sendMessagePromise<Record<string, RawHistoryState[]>>({
    type: "history/history_during_period",
    start_time: start,
    entity_ids: [entityId],
    minimal_response: true,
    no_attributes: true,
  });
  const series = result?.[entityId] ?? [];
  return series
    .map((p) => ({ t: sampleTime(p), state: String(p.s ?? p.state ?? "") }))
    .sort((a, b) => a.t - b.t);
}

/** Prefix a root-relative HA/Frigate path with the connected origin (Settings may
 *  point straight at HA, where the page origin would 404). Empty/absolute → as-is. */
function withBase(path: string, baseUrl: string): string {
  if (!baseUrl || !path.startsWith("/")) return path;
  return `${baseUrl.replace(/\/+$/, "")}${path}`;
}

/**
 * Ask HA for an on-demand stream URL for a camera. `camera/stream` returns a
 * signed, root-relative HLS playlist path (`/api/hls/<token>/master.m3u8`) that
 * HA serves under the same `/api` the nginx pod already proxies. Resolved
 * against the connected origin like camera snapshots.
 */
async function fetchStreamUrl(
  conn: Connection,
  entityId: string,
  baseUrl: string,
  format: "hls",
): Promise<string | null> {
  try {
    const res = await conn.sendMessagePromise<{ url?: string }>({
      type: "camera/stream",
      entity_id: entityId,
      format,
    });
    return res?.url ? withBase(res.url, baseUrl) : null;
  } catch {
    // Camera can't produce a stream (or HA lacks the stream integration) — the
    // player falls back to MJPEG/snapshot, so don't surface this as an error.
    return null;
  }
}

/**
 * Read recorded events for a Frigate camera over `[startMs, endMs]`. Frigate's
 * HA integration exposes its API under `/api/frigate/…` (same-origin through the
 * nginx proxy). Degrades to [] if Frigate isn't installed yet or the request
 * fails, so the timeline renders empty rather than throwing.
 */
async function fetchFrigateEvents(
  creds: HaCredentials,
  camera: string,
  startMs: number,
  endMs: number,
): Promise<CameraEvent[]> {
  const base = withBase(FRIGATE_BASE, creds.url);
  const url =
    `${base}/events?camera=${encodeURIComponent(camera)}` +
    `&after=${Math.floor(startMs / 1000)}&before=${Math.floor(endMs / 1000)}&limit=500`;
  try {
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${creds.token}` },
    });
    if (!res.ok) return [];
    const raw = (await res.json()) as RawFrigateEvent[];
    return normalizeFrigateEvents(raw ?? [], base);
  } catch {
    return [];
  }
}

/**
 * Live Home Assistant source over the WebSocket API. `home-assistant-js-websocket`
 * handles auth + automatic reconnect; we mirror its entity stream into the store
 * and resolve areas from the registries. Re-fetches areas on every (re)connect.
 */
export function createHaSource(
  creds: HaCredentials,
  deps: HaSourceDeps = defaultDeps,
): Source {
  let conn: Connection | null = null;
  let unsub: (() => void) | null = null;
  let stopped = false;

  const store = () => useEntityStore.getState();

  async function loadAreas() {
    if (!conn) return;
    try {
      const { areas, devices } = await fetchRegistry(conn);
      store().setAreas(areas);
      store().setDevices(devices);
    } catch {
      // Registry unavailable (older HA / limited token) — keep entities,
      // they group under "Unassigned" rather than failing the connection.
    }
  }

  return {
    async start() {
      stopped = false;
      // Camera <img> URLs resolve against this so they reach HA even when the
      // app isn't served through HA's reverse proxy (Settings → direct HA URL).
      store().setBaseUrl(creds.url);
      store().setStatus("connecting");

      try {
        conn = await deps.connect(creds);
      } catch (err) {
        store().setStatus("error", describeError(err));
        return;
      }
      if (stopped) {
        conn.close();
        return;
      }

      // "ready" fires on initial connect and after every auto-reconnect.
      conn.addEventListener("ready", () => {
        store().setStatus("connected");
        void loadAreas();
      });
      conn.addEventListener("disconnected", () => {
        store().setStatus("connecting", "Reconnecting…");
      });

      unsub = deps.subscribe(conn, (entities) => {
        store().setEntities(toEntityRecord(entities));
      });

      store().setStatus("connected");
      await loadAreas();
    },
    stop() {
      stopped = true;
      unsub?.();
      unsub = null;
      conn?.close();
      conn = null;
    },
    async callService(domain, service, data = {}) {
      if (!conn) throw new Error("Not connected to Home Assistant.");
      const { entity_id, ...serviceData } = data;
      // HA echoes the resulting state change back over the entity subscription,
      // which reconciles the store — no optimistic write needed here.
      await haCallService(
        conn,
        domain,
        service,
        serviceData,
        entity_id ? { entity_id } : undefined,
      );
    },
    async fetchHistory(entityId, hours) {
      if (!conn) throw new Error("Not connected to Home Assistant.");
      return fetchEntityHistory(conn, entityId, hours);
    },
    async fetchLogbook(startMs, endMs, opts) {
      if (!conn) throw new Error("Not connected to Home Assistant.");
      return fetchLogbook(conn, startMs, endMs, opts?.entityIds);
    },
    async streamUrl(entityId, format = "hls") {
      if (!conn) return null;
      return fetchStreamUrl(conn, entityId, store().baseUrl, format);
    },
    async fetchCameraEvents(camera, startMs, endMs) {
      return fetchFrigateEvents(creds, camera, startMs, endMs);
    },
    recordingUrlAt(camera, startMs, endMs) {
      return buildRecordingUrl(camera, startMs, endMs, withBase(FRIGATE_BASE, creds.url));
    },
    eventClipUrl(eventId) {
      return buildEventClipUrl(eventId, withBase(FRIGATE_BASE, creds.url));
    },
    async getAutomationConfig(id) {
      const res = await fetch(automationUrl(creds, id), {
        headers: { Authorization: `Bearer ${creds.token}` },
      });
      if (res.status === 404) return null;
      if (!res.ok) throw new Error(describeConfigError(res.status));
      return (await res.json()) as AutomationConfig;
    },
    async saveAutomationConfig(config) {
      const res = await fetch(automationUrl(creds, config.id), {
        method: "POST",
        headers: {
          Authorization: `Bearer ${creds.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(config),
      });
      if (!res.ok) throw new Error(describeConfigError(res.status));
    },
    async deleteAutomationConfig(id) {
      const res = await fetch(automationUrl(creds, id), {
        method: "DELETE",
        headers: { Authorization: `Bearer ${creds.token}` },
      });
      if (!res.ok) throw new Error(describeConfigError(res.status));
    },
  };
}

import {
  createConnection,
  createLongLivedTokenAuth,
  subscribeEntities,
  callService as haCallService,
  ERR_INVALID_AUTH,
  ERR_CANNOT_CONNECT,
  type Connection,
  type HassEntities,
  type UnsubscribeFunc,
} from "home-assistant-js-websocket";
import { useEntityStore } from "./entityStore";
import type { HassEntity } from "../lib/ha";
import type { HistoryPoint, Source, WebRtcSignal } from "./source";
import type { AutomationConfig } from "../lib/automations";
import { capLogbook, normalizeLogbook, type LogEvent, type RawLogbookEntry } from "../lib/logbook";
import {
  normalizeFrigateEvents,
  parseFrigateWsEvents,
  recordingUrlAt as buildRecordingUrl,
  eventClipUrl as buildEventClipUrl,
  clipExportUrl as buildClipExportUrl,
  FRIGATE_BASE,
  type CameraEvent,
} from "../lib/cameraEvents";
import { clipRangeSeconds } from "../lib/clipExport";
import { dedupeRingMqtt } from "../lib/dedupe";
import { parseFrigateWsRecordings } from "../lib/ringFootage";
import {
  buildAreaRegistry,
  buildDeviceIndex,
  buildEntityCategories,
  buildEntityPlatforms,
  buildZWaveEntityIds,
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
 * HA events that invalidate anything `fetchRegistry` derives. Entity *states* stream in over
 * `subscribe_entities`, but area/device/category/platform metadata does not — without these a
 * device added mid-session shows up with no room (grouped under "Unassigned") until the socket
 * happens to reconnect. That is exactly what a new Z-Wave node looks like: present in Devices,
 * missing from its room.
 */
const REGISTRY_EVENTS = [
  "area_registry_updated",
  "device_registry_updated",
  "entity_registry_updated",
] as const;

/**
 * Adding one device fires a burst (one device event + one entity event per entity it owns, and
 * an area event if it was placed). Coalesce them so a 35-entity Z-Wave node costs one registry
 * refetch rather than 36.
 */
const REGISTRY_REFRESH_DEBOUNCE_MS = 500;

/**
 * Pull the three registries once and resolve BOTH the entity → area-name map and
 * the richer device index (manufacturer/model/firmware + entity ownership) the
 * Devices hub needs.
 */
async function fetchRegistry(conn: Connection): Promise<{
  areas: ReturnType<typeof buildAreaRegistry>;
  devices: DeviceIndex;
  categories: Record<string, string>;
  zwaveEntityIds: string[];
  entityPlatforms: Record<string, string>;
}> {
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
    categories: buildEntityCategories(entities),
    zwaveEntityIds: buildZWaveEntityIds(entities),
    entityPlatforms: buildEntityPlatforms(entities),
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
  // Capped here rather than in the screen: this instance records ~98,000 state rows a day, so the
  // 30d range is a month of them in one response. The Android twin died rendering that (see
  // `capLogbook`); web survived it but was doing the same unbounded work.
  return capLogbook(normalizeLogbook(raw ?? [])).events;
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
  return `${creds.url.replace(/\/+$/, "")}/api/config/automation/config/${encodeURIComponent(id)}`;
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

/** How long we give HA to produce an HLS stream URL before stepping down. */
const STREAM_URL_TIMEOUT_MS = 15_000;

/** Signing is one websocket round trip; if it stalls, fall back rather than block playback. */
const SIGN_PATH_TIMEOUT_MS = 10_000;

/**
 * Lifetime of a VOD signature, in seconds. Mirrors Android's `SIGN_PATH_EXPIRY_SECONDS`.
 *
 * One hour, deliberately shorter than the retention the timeline spans: the signature is a bearer
 * credential embedded in a URL, so it should not outlive a plausible viewing session. Pages are
 * re-signed as the playhead moves between them, so this only bites a session that sits on ONE
 * page for over an hour — which then surfaces as a playback error rather than silently.
 */
const SIGN_PATH_EXPIRY_SECONDS = 3_600;

/**
 * Mint an HA **signed path** for `unsigned`, or null if it can't be minted.
 *
 * A signed URL carries its own authorisation in the query string (`?authSig=`), which is what lets
 * a URL be handed to something that cannot set an `Authorization` header — hls.js's segment
 * fetches, and the browser's own downloader.
 *
 * Shared by the two callers deliberately: the fallback semantics and the two constants above are
 * load-bearing, and a second copy of this would be a second place for them to drift. What differs
 * between the callers is what they do with a **null** — see each of them — so this returns null
 * rather than deciding on their behalf.
 */
async function signPath(
  socket: Connection | null,
  unsigned: string,
  baseUrl: string,
): Promise<string | null> {
  // auth/sign_path wants a server-relative path, not the absolute URL the caller gets.
  let path: string;
  try {
    path = new URL(unsigned, globalThis.location?.origin ?? "http://localhost").pathname;
  } catch {
    return null;
  }
  if (!socket) return null;
  try {
    const res = await Promise.race([
      socket.sendMessagePromise<{ path?: string }>({
        type: "auth/sign_path",
        path,
        expires: SIGN_PATH_EXPIRY_SECONDS,
      }),
      new Promise<null>((resolve) => setTimeout(() => resolve(null), SIGN_PATH_TIMEOUT_MS)),
    ]);
    return res?.path ? withBase(res.path, baseUrl) : null;
  } catch {
    return null;
  }
}

/**
 * Ask HA for an on-demand stream URL for a camera. `camera/stream` returns a
 * signed, root-relative HLS playlist path (`/api/hls/<token>/master.m3u8`) that
 * HA serves under the same `/api` the nginx pod already proxies. Resolved
 * against the connected origin like camera snapshots.
 *
 * Bounded at 15s: HA answers only after the camera's stream pipeline is up, and
 * a battery Ring camera being woken by go2rtc can block this for the better
 * part of a minute. Null already means "step down the transport ladder" to the
 * caller, so a timeout degrades to MJPEG/snapshot instead of hanging the player.
 */
async function fetchStreamUrl(
  conn: Connection,
  entityId: string,
  baseUrl: string,
  format: "hls",
): Promise<string | null> {
  try {
    const res = await Promise.race([
      conn.sendMessagePromise<{ url?: string }>({
        type: "camera/stream",
        entity_id: entityId,
        format,
      }),
      new Promise<null>((resolve) =>
        setTimeout(() => resolve(null), STREAM_URL_TIMEOUT_MS),
      ),
    ]);
    return res?.url ? withBase(res.url, baseUrl) : null;
  } catch {
    // Camera can't produce a stream (or HA lacks the stream integration) — the
    // player falls back to MJPEG/snapshot, so don't surface this as an error.
    return null;
  }
}

/**
 * Read recorded events for a Frigate camera over `[startMs, endMs]`, via the integration's
 * `frigate/events/get` **websocket command**. Degrades to [] on any failure, so the timeline
 * renders empty rather than throwing.
 *
 * ## Why the websocket and not `GET /api/frigate/events`
 *
 * **That REST route does not exist and never did.** frigate-hass-integration proxies media
 * (snapshot/recording/vod/clips/…) over REST but exposes its *query* API as websocket commands
 * (`ws_api.py`: `frigate/events/get`, `frigate/recordings/get`, …). The old fetch here 404'd on
 * every call and the catch turned that into [], so every Frigate camera's timeline silently
 * rendered without a single event chip — on both platforms, since Android was a 1:1 port of the
 * same wrong call. The same "the tests stubbed a route that never existed" failure as the
 * `/api/frigate/config` bug this file already documents; verified against the integration's
 * registered views AND the working websocket command on the live cluster, 2026-07-30.
 *
 * `instance_id` is the Frigate `client_id` the integration stamps on the camera entity — read
 * from the store rather than hardcoded, so a renamed instance keeps working. The result arrives
 * as a JSON **string** (the integration skips decoding), hence the manual parse.
 */
async function fetchFrigateEvents(
  conn: Connection,
  instanceId: string,
  camera: string,
  startMs: number,
  endMs: number,
  baseUrl: string,
): Promise<CameraEvent[]> {
  try {
    const result = await conn.sendMessagePromise<unknown>({
      type: "frigate/events/get",
      instance_id: instanceId,
      cameras: [camera],
      after: Math.floor(startMs / 1000),
      before: Math.floor(endMs / 1000),
      limit: 500,
    });
    return normalizeFrigateEvents(parseFrigateWsEvents(result), withBase(FRIGATE_BASE, baseUrl));
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
  let registryUnsubs: UnsubscribeFunc[] = [];
  let registryTimer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;
  // The last UN-deduped narrowing, kept so `toEntityRecord` can reuse unchanged entity objects
  // (see the identity note there). Deliberately not `store().entities`: that map has already had
  // the shadowed ring twins removed, so feeding it back would rebuild those every push.
  let lastRecord: Record<string, HassEntity> = {};

  const store = () => useEntityStore.getState();

  async function loadAreas() {
    if (!conn) return;
    try {
      const { areas, devices, categories, zwaveEntityIds, entityPlatforms } =
        await fetchRegistry(conn);
      store().setAreas(areas);
      store().setDevices(devices);
      store().setCategories(categories);
      store().setZWaveEntityIds(zwaveEntityIds);
      store().setEntityPlatforms(entityPlatforms);
      // Platforms can arrive after the first entity push — re-filter what's shown.
      store().setEntities(dedupeRingMqtt(store().entities, entityPlatforms));
    } catch {
      // Registry unavailable (older HA / limited token) — keep entities,
      // they group under "Unassigned" rather than failing the connection.
    }
  }

  /** Coalesce a burst of registry events into one refetch. */
  function scheduleRegistryRefresh() {
    if (stopped) return;
    if (registryTimer) clearTimeout(registryTimer);
    registryTimer = setTimeout(() => {
      registryTimer = null;
      void loadAreas();
    }, REGISTRY_REFRESH_DEBOUNCE_MS);
  }

  /**
   * Keep the registries live for the life of the connection. `subscribeEvents` re-subscribes
   * itself across auto-reconnects, so this is set up once per `start()`.
   */
  async function subscribeRegistryUpdates() {
    if (!conn || registryUnsubs.length) return;
    try {
      const subs = await Promise.all(
        REGISTRY_EVENTS.map((type) =>
          conn!.subscribeEvents(scheduleRegistryRefresh, type),
        ),
      );
      // `stop()` may have run while we were awaiting — don't leak the subscriptions.
      if (stopped) {
        subs.forEach((u) => void u());
        return;
      }
      registryUnsubs = subs;
    } catch {
      // Older HA or a restricted token — fall back to the previous behaviour, where the
      // registries refresh only on (re)connect. Entities still stream normally.
    }
  }

  function teardownRegistryUpdates() {
    if (registryTimer) {
      clearTimeout(registryTimer);
      registryTimer = null;
    }
    registryUnsubs.forEach((u) => void u());
    registryUnsubs = [];
  }

  // The Frigate `instance_id` the websocket query commands require — the same `client_id` the
  // integration stamps on the camera entity (what `isFrigateCamera` matches on). Read from state
  // rather than hardcoded so a renamed Frigate instance keeps working.
  function frigateInstanceId(camera: string): string {
    const clientId = store().entities[`camera.${camera}`]?.attributes?.client_id;
    return typeof clientId === "string" && clientId ? clientId : "frigate";
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
      conn.addEventListener("reconnect-error", (_c, err) => {
        if (!stopped) store().setStatus("error", describeError(err));
      });

      unsub = deps.subscribe(conn, (entities) => {
        // Central dedupe: every consumer (Home, Devices, camera wall) sees one
        // entity per physical device even while Ring + ring-mqtt are both live.
        lastRecord = toEntityRecord(entities, lastRecord);
        store().setEntities(dedupeRingMqtt(lastRecord, store().entityPlatforms));
      });

      store().setStatus("connected");
      await loadAreas();
      await subscribeRegistryUpdates();
    },
    stop() {
      stopped = true;
      teardownRegistryUpdates();
      unsub?.();
      unsub = null;
      conn?.close();
      conn = null;
      lastRecord = {};
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
      if (!conn) return [];
      return fetchFrigateEvents(conn, frigateInstanceId(camera), camera, startMs, endMs, store().baseUrl);
    },
    async fetchCameraFootage(camera, startMs, endMs) {
      // `frigate/recordings/get` — websocket-only, like events (see fetchFrigateEvents). One
      // entry per ~10s recording segment; parseFrigateWsRecordings coalesces to drawable spans.
      const socket = conn;
      if (!socket) return [];
      try {
        const result = await socket.sendMessagePromise<unknown>({
          type: "frigate/recordings/get",
          instance_id: frigateInstanceId(camera),
          camera,
          after: Math.floor(startMs / 1000),
          before: Math.floor(endMs / 1000),
        });
        return parseFrigateWsRecordings(result);
      } catch {
        return [];
      }
    },
    recordingUrlAt(camera, startMs, endMs) {
      return buildRecordingUrl(camera, startMs, endMs, withBase(FRIGATE_BASE, creds.url));
    },
    async signedRecordingUrlAt(camera, startMs, endMs) {
      const unsigned = buildRecordingUrl(
        camera,
        startMs,
        endMs,
        withBase(FRIGATE_BASE, creds.url),
      );
      // Unsignable → hand back the unsigned URL rather than throwing. That is the behaviour from
      // before signing existed, so the worst case is the original bug (a black recorded view)
      // rather than an empty player.
      return (await signPath(conn, unsigned, creds.url)) ?? unsigned;
    },
    eventClipUrl(eventId) {
      return buildEventClipUrl(eventId, withBase(FRIGATE_BASE, creds.url));
    },
    async signedClipExportUrl(camera, startMs, endMs) {
      const { startSec, endSec } = clipRangeSeconds({ startMs, endMs });
      const unsigned = buildClipExportUrl(
        camera,
        startSec,
        endSec,
        withBase(FRIGATE_BASE, creds.url),
      );
      // Null, NOT the unsigned URL — the opposite of signedRecordingUrlAt above, on purpose.
      // RecordingProxyView 401s an unsigned request outright (it has none of the VOD playlist's
      // leniency), and this URL is handed to the browser's downloader, where a 401 lands as a
      // silently failed entry in the download shelf with nothing the app can catch. Refusing up
      // front lets the UI say why.
      return signPath(conn, unsigned, creds.url);
    },
    async webrtcOffer(entityId, offerSdp, onSignal) {
      if (!conn) throw new Error("Not connected to Home Assistant.");
      // `camera/webrtc/offer` is a subscribe-style command: HA streams back the
      // session id, the SDP answer, and trickle ICE candidates (or an error).
      const unsubscribe = await conn.subscribeMessage<WebRtcSignal>(
        (msg) => onSignal(msg),
        { type: "camera/webrtc/offer", entity_id: entityId, offer: offerSdp },
      );
      return { unsubscribe };
    },
    async webrtcCandidate(entityId, sessionId, candidate) {
      if (!conn) throw new Error("Not connected to Home Assistant.");
      // `entity_id` is required by HA alongside the session id; without it HA
      // rejects the candidate and ICE never completes (live view goes stale).
      await conn.sendMessagePromise({
        type: "camera/webrtc/candidate",
        entity_id: entityId,
        session_id: sessionId,
        candidate,
      });
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

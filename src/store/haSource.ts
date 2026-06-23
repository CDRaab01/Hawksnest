import {
  createConnection,
  createLongLivedTokenAuth,
  subscribeEntities,
  ERR_INVALID_AUTH,
  ERR_CANNOT_CONNECT,
  type Connection,
  type HassEntities,
} from "home-assistant-js-websocket";
import { useEntityStore } from "./entityStore";
import type { Source } from "./source";
import {
  buildAreaRegistry,
  toEntityRecord,
  type AreaRegistryEntry,
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

/** Pull the three registries and resolve entity → area names. */
async function fetchAreas(conn: Connection) {
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
  return buildAreaRegistry(areas, entities, devices);
}

function describeError(err: unknown): string {
  if (err === ERR_INVALID_AUTH) return "Invalid access token.";
  if (err === ERR_CANNOT_CONNECT) return "Can't reach Home Assistant at that URL.";
  return "Connection to Home Assistant failed.";
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
      store().setAreas(await fetchAreas(conn));
    } catch {
      // Registry unavailable (older HA / limited token) — keep entities,
      // they group under "Unassigned" rather than failing the connection.
    }
  }

  return {
    async start() {
      stopped = false;
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
  };
}

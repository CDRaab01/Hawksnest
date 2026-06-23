import { useEntityStore } from "./entityStore";
import type { Source } from "./source";

export interface HaCredentials {
  url: string;
  token: string;
}

/**
 * Live Home Assistant source — Phase 1 stub.
 *
 * The real implementation (next phase) will, via `home-assistant-js-websocket`:
 *   1. const auth = createLongLivedTokenAuth(creds.url, creds.token)
 *   2. const conn = await createConnection({ auth })
 *   3. subscribeEntities(conn, (ents) => store.setSnapshot(toRecord(ents), areas))
 *   4. fetch the area + entity + device registries -> AreaRegistry
 *   5. conn.addEventListener("ready"/"disconnected", ...) -> store.setStatus(...)
 *
 * It is intentionally NOT wired to a live server here: the owner's HA is only
 * reachable on their LAN / Tailscale (192.168.4.34), not from this build env, so
 * it can't be verified in CI. Selecting this source today reports a clear error.
 */
export function createHaSource(creds: HaCredentials): Source {
  return {
    async start() {
      const store = useEntityStore.getState();
      store.setStatus("connecting");
      store.setStatus(
        "error",
        `Live connection to ${creds.url} is not implemented yet (Phase 1).`,
      );
    },
    stop() {},
  };
}

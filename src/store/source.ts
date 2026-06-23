/** Optional service-call data. `entity_id` targets the entity; the rest is service data. */
export type ServiceData = { entity_id?: string } & Record<string, unknown>;

/**
 * One historical sample for an entity. `t` is epoch milliseconds; `state` is the
 * raw HA state string at that time (parse to a number for charting where it makes
 * sense — see Sparkline). Returned oldest-first.
 */
export interface HistoryPoint {
  t: number;
  state: string;
}

/**
 * A data Source feeds the entity store and (optionally) carries out control
 * actions. Phase 0/2 use the fixture source; the live HA source (WebSocket)
 * lands behind the same interface, so no screen or card changes when we swap.
 */
export interface Source {
  start: () => void | Promise<void>;
  stop: () => void;
  /**
   * Perform an HA service call (e.g. lock.lock, light.turn_on). The fixture
   * source simulates it locally; the live source forwards it to HA, whose state
   * echo reconciles the store. Throws if the source can't perform writes.
   */
  callService?: (
    domain: string,
    service: string,
    data?: ServiceData,
  ) => Promise<void>;
  /**
   * Fetch the recent state history for one entity over the last `hours`. The
   * live source asks HA over the WebSocket; the fixture source synthesizes a
   * plausible series. Rejects if the source can't provide history.
   */
  fetchHistory?: (entityId: string, hours: number) => Promise<HistoryPoint[]>;
}

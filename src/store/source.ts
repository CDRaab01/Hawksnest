/**
 * A data Source feeds the entity store. Phase 0/2 use the fixture source; the
 * live HA source (WebSocket) lands in Phase 1 behind the same interface, so no
 * screen or card changes when we swap.
 */
export interface Source {
  start: () => void | Promise<void>;
  stop: () => void;
}

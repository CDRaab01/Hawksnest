import { describe, it, expect, beforeEach } from "vitest";
import { useEntityStore } from "../entityStore";
import type { HassEntity } from "../../lib/ha";

const entity = (id: string, state: string): HassEntity =>
  ({ entity_id: id, state, attributes: {} }) as HassEntity;

beforeEach(() => {
  useEntityStore.setState({
    entities: {},
    areas: {},
    status: "connecting",
    error: undefined,
    lastConnectedAt: undefined,
    staleSince: undefined,
  });
});

/**
 * Pins the honest-offline transitions in the entity store: leaving "connected" stamps the
 * last-connected/stale-since clocks and immediately masks lock/alarm states (the "never render
 * a stale lock" invariant); getting live again clears the grace clock. All in-memory only —
 * there is deliberately no persistence here to test.
 */
describe("entityStore.setStatus — offline bookkeeping", () => {
  function connectWith(...entities: HassEntity[]) {
    const s = useEntityStore.getState();
    s.setEntities(Object.fromEntries(entities.map((e) => [e.entity_id, e])));
    s.setStatus("connected");
  }

  it("stamps lastConnectedAt + staleSince and masks lock/alarm on a drop", () => {
    connectWith(
      entity("lock.front", "locked"),
      entity("alarm_control_panel.home", "armed_home"),
      entity("light.porch", "on"),
    );
    const before = Date.now();

    useEntityStore.getState().setStatus("connecting", "Reconnecting…");

    const s = useEntityStore.getState();
    expect(s.lastConnectedAt).toBeGreaterThanOrEqual(before);
    expect(s.staleSince).toBe(s.lastConnectedAt);
    // Security invariant: lock/alarm collapse immediately; the light keeps last-known.
    expect(s.entities["lock.front"].state).toBe("unavailable");
    expect(s.entities["alarm_control_panel.home"].state).toBe("unavailable");
    expect(s.entities["light.porch"].state).toBe("on");
  });

  it("does not restamp staleSince across repeated reconnect updates", () => {
    connectWith(entity("light.porch", "on"));
    useEntityStore.getState().setStatus("connecting");
    const first = useEntityStore.getState().staleSince;
    useEntityStore.getState().setStatus("connecting", "Reconnecting…");
    expect(useEntityStore.getState().staleSince).toBe(first);
  });

  it("clears staleSince once reconnected (lastConnectedAt keeps the drop stamp)", () => {
    connectWith(entity("lock.front", "locked"));
    useEntityStore.getState().setStatus("connecting");
    useEntityStore.getState().setStatus("connected");
    const s = useEntityStore.getState();
    expect(s.staleSince).toBeUndefined();
    expect(s.lastConnectedAt).toBeDefined();
  });

  it("starts no grace window on a first-ever connect failure", () => {
    useEntityStore.getState().setStatus("error", "Can't reach Home Assistant at that URL.");
    const s = useEntityStore.getState();
    expect(s.lastConnectedAt).toBeUndefined();
    expect(s.staleSince).toBeUndefined();
  });

  it("starts no grace window when there were no entities to keep showing", () => {
    useEntityStore.getState().setStatus("connected");
    useEntityStore.getState().setStatus("connecting");
    const s = useEntityStore.getState();
    expect(s.lastConnectedAt).toBeDefined();
    expect(s.staleSince).toBeUndefined();
  });

  it("clears the grace clock when falling back to demo data", () => {
    connectWith(entity("lock.front", "locked"));
    useEntityStore.getState().setStatus("connecting");
    useEntityStore.getState().setStatus("demo");
    expect(useEntityStore.getState().staleSince).toBeUndefined();
  });
});

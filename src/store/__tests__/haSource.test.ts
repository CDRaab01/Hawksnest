import { describe, it, expect, beforeEach } from "vitest";
import {
  ERR_INVALID_AUTH,
  type Connection,
  type HassEntities,
} from "home-assistant-js-websocket";
import { createHaSource, type HaSourceDeps } from "../haSource";
import { useEntityStore } from "../entityStore";

const REGISTRIES: Record<string, unknown> = {
  "config/area_registry/list": [{ area_id: "a1", name: "Front Door" }],
  "config/entity_registry/list": [
    { entity_id: "lock.front", area_id: "a1", device_id: null },
    { entity_id: "sensor.x", area_id: null, device_id: "d1" },
  ],
  "config/device_registry/list": [
    {
      id: "d1",
      area_id: "a1",
      name: "Front Door Sensor",
      manufacturer: "Acme",
      model: "DW-1",
      sw_version: "1.2.3",
    },
  ],
};

// Logbook payload HA returns from logbook/get_events (epoch-second `when`).
const LOGBOOK: Record<string, unknown> = {
  "logbook/get_events": [
    { when: 1_700_000_000, name: "Front Door", message: "was unlocked", entity_id: "lock.front" },
  ],
};

const ENTITIES = {
  "lock.front": { entity_id: "lock.front", state: "locked", attributes: {} },
  "sensor.x": { entity_id: "sensor.x", state: "42", attributes: {} },
} as unknown as HassEntities;

// Compressed history shape HA returns from history/history_during_period.
const HISTORY: Record<string, unknown> = {
  "history/history_during_period": {
    "sensor.x": [
      { s: "40", lu: 1_700_000_000 },
      { s: "42", lu: 1_700_003_600 },
    ],
  },
};

function makeFakeConn() {
  const listeners: Record<string, Array<() => void>> = {};
  const sent: Array<Record<string, unknown>> = [];
  const conn = {
    addEventListener: (ev: string, cb: () => void) => {
      (listeners[ev] ||= []).push(cb);
    },
    removeEventListener: () => {},
    sendMessagePromise: async (msg: { type: string }) => {
      sent.push(msg as Record<string, unknown>);
      return REGISTRIES[msg.type] ?? HISTORY[msg.type] ?? LOGBOOK[msg.type];
    },
    close: () => {},
  } as unknown as Connection;
  return {
    conn,
    sent,
    emit: (ev: string) => (listeners[ev] || []).forEach((f) => f()),
  };
}

beforeEach(() => {
  useEntityStore.setState({
    entities: {},
    areas: {},
    status: "connecting",
    error: undefined,
  });
});

describe("createHaSource", () => {
  it("connects, streams entities, and resolves areas (incl. device fallback)", async () => {
    const { conn } = makeFakeConn();
    const deps: HaSourceDeps = {
      connect: async () => conn,
      subscribe: (_c, cb) => {
        cb(ENTITIES);
        return () => {};
      },
    };
    await createHaSource({ url: "http://ha", token: "t" }, deps).start();

    const s = useEntityStore.getState();
    expect(s.status).toBe("connected");
    expect(s.entities["lock.front"].state).toBe("locked");
    expect(s.areas["lock.front"]).toBe("Front Door");
    expect(s.areas["sensor.x"]).toBe("Front Door"); // via device d1
  });

  it("retains device records resolved from the registries", async () => {
    const { conn } = makeFakeConn();
    const deps: HaSourceDeps = {
      connect: async () => conn,
      subscribe: (_c, cb) => {
        cb(ENTITIES);
        return () => {};
      },
    };
    await createHaSource({ url: "http://ha", token: "t" }, deps).start();

    const { devices } = useEntityStore.getState();
    expect(devices.devices["d1"]).toMatchObject({
      name: "Front Door Sensor",
      area: "Front Door",
      manufacturer: "Acme",
      model: "DW-1",
      swVersion: "1.2.3",
    });
    expect(devices.devices["d1"].entityIds).toContain("sensor.x");
    expect(devices.entityToDevice["sensor.x"]).toBe("d1");
  });

  it("fetches the logbook over the WebSocket and normalizes it", async () => {
    const { conn, sent } = makeFakeConn();
    const deps: HaSourceDeps = {
      connect: async () => conn,
      subscribe: () => () => {},
    };
    const source = createHaSource({ url: "http://ha", token: "t" }, deps);
    await source.start();
    const events = await source.fetchLogbook!(1_699_999_000_000, 1_700_001_000_000);

    const req = sent.find((m) => m.type === "logbook/get_events");
    expect(req).toMatchObject({ type: "logbook/get_events" });
    expect(events).toEqual([
      {
        when: 1_700_000_000_000,
        name: "Front Door",
        message: "was unlocked",
        entityId: "lock.front",
        domain: "lock",
        state: null,
      },
    ]);
  });

  it("reports a clear error on invalid auth and does not connect", async () => {
    const deps: HaSourceDeps = {
      connect: async () => {
        throw ERR_INVALID_AUTH;
      },
      subscribe: () => () => {},
    };
    await createHaSource({ url: "http://ha", token: "bad" }, deps).start();

    const s = useEntityStore.getState();
    expect(s.status).toBe("error");
    expect(s.error).toBe("Invalid access token.");
  });

  it("forwards control actions as call_service messages", async () => {
    const { conn, sent } = makeFakeConn();
    const deps: HaSourceDeps = {
      connect: async () => conn,
      subscribe: () => () => {},
    };
    const source = createHaSource({ url: "http://ha", token: "t" }, deps);
    await source.start();
    await source.callService!("lock", "unlock", {
      entity_id: "lock.front_door_lock",
    });

    const call = sent.find((m) => m.type === "call_service");
    expect(call).toMatchObject({
      type: "call_service",
      domain: "lock",
      service: "unlock",
      target: { entity_id: "lock.front_door_lock" },
    });
  });

  it("fetches entity history over the WebSocket and maps it to points", async () => {
    const { conn, sent } = makeFakeConn();
    const deps: HaSourceDeps = {
      connect: async () => conn,
      subscribe: () => () => {},
    };
    const source = createHaSource({ url: "http://ha", token: "t" }, deps);
    await source.start();
    const points = await source.fetchHistory!("sensor.x", 24);

    const req = sent.find((m) => m.type === "history/history_during_period");
    expect(req).toMatchObject({ entity_ids: ["sensor.x"], minimal_response: true });
    expect(points).toEqual([
      { t: 1_700_000_000_000, state: "40" },
      { t: 1_700_003_600_000, state: "42" },
    ]);
  });

  it("flags reconnecting when the connection drops", async () => {
    const { conn, emit } = makeFakeConn();
    const deps: HaSourceDeps = {
      connect: async () => conn,
      subscribe: () => () => {},
    };
    await createHaSource({ url: "http://ha", token: "t" }, deps).start();
    expect(useEntityStore.getState().status).toBe("connected");

    emit("disconnected");
    expect(useEntityStore.getState().status).toBe("connecting");
  });
});

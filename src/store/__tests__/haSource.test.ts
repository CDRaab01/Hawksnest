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
  "config/device_registry/list": [{ id: "d1", area_id: "a1" }],
};

const ENTITIES = {
  "lock.front": { entity_id: "lock.front", state: "locked", attributes: {} },
  "sensor.x": { entity_id: "sensor.x", state: "42", attributes: {} },
} as unknown as HassEntities;

function makeFakeConn() {
  const listeners: Record<string, Array<() => void>> = {};
  const conn = {
    addEventListener: (ev: string, cb: () => void) => {
      (listeners[ev] ||= []).push(cb);
    },
    removeEventListener: () => {},
    sendMessagePromise: async (msg: { type: string }) => REGISTRIES[msg.type],
    close: () => {},
  } as unknown as Connection;
  return { conn, emit: (ev: string) => (listeners[ev] || []).forEach((f) => f()) };
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

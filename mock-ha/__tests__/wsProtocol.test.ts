import { describe, it, expect, beforeEach } from "vitest";
import { MockHub, Session, type Transport } from "../wsProtocol";
import { getScenario } from "../scenarios";

type Sent = Record<string, unknown>;

function harness(scenarioName = "default") {
  const sent: Sent[] = [];
  let closed = false;
  const transport: Transport = {
    send: (m) => sent.push(m as Sent),
    close: () => {
      closed = true;
    },
  };
  const hub = new MockHub(getScenario(scenarioName));
  const session = new Session(hub, transport);
  return {
    hub,
    session,
    sent,
    isClosed: () => closed,
    last: (type: string) => [...sent].reverse().find((m) => m.type === type),
  };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

function authed(scenarioName = "default") {
  const h = harness(scenarioName);
  h.session.handleMessage({ type: "auth", access_token: "any-token" });
  return h;
}

describe("mock HA ws protocol", () => {
  beforeEach(() => {
    // ctx ids/timestamps are derived at runtime; nothing global to reset.
  });

  it("announces auth_required on connect", () => {
    const h = harness();
    expect(h.sent[0]).toMatchObject({ type: "auth_required", ha_version: "2024.12.0" });
  });

  it("accepts a non-empty token with auth_ok", () => {
    const h = harness();
    h.session.handleMessage({ type: "auth", access_token: "x" });
    expect(h.last("auth_ok")).toMatchObject({ type: "auth_ok", ha_version: "2024.12.0" });
    expect(h.isClosed()).toBe(false);
  });

  it("rejects empty tokens and closes", () => {
    const h = harness();
    h.session.handleMessage({ type: "auth", access_token: "" });
    expect(h.last("auth_invalid")).toBeTruthy();
    expect(h.isClosed()).toBe(true);
  });

  it("rejects all auth in the bad-token scenario", () => {
    const h = harness("bad-token");
    h.session.handleMessage({ type: "auth", access_token: "any-token" });
    expect(h.last("auth_invalid")).toBeTruthy();
    expect(h.isClosed()).toBe(true);
  });

  it("acks supported_features (id 1)", () => {
    const h = authed();
    h.session.handleMessage({ type: "supported_features", id: 1 });
    expect(h.last("result")).toMatchObject({ type: "result", id: 1, success: true });
  });

  it("acks subscribe_entities then pushes the full set as an `a` event", () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 7 });
    expect(h.last("result")).toMatchObject({ id: 7, success: true });
    const event = h.last("event") as { id: number; event: { a: Record<string, { s: string; lc: number }> } };
    expect(event.id).toBe(7);
    const lock = event.event.a["lock.front_door_lock"];
    expect(lock.s).toBe("locked");
    expect(typeof lock.lc).toBe("number");
  });

  it("echoes the requested state after a confirmed call_service", async () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 2 });
    h.hub.setServiceOutcome({ domain: "lock", service: "unlock", outcome: "confirm", delayMs: 0 });
    h.session.handleMessage({
      type: "call_service",
      id: 3,
      domain: "lock",
      service: "unlock",
      target: { entity_id: "lock.front_door_lock" },
    });
    // The call itself succeeds immediately...
    expect(h.last("result")).toMatchObject({ id: 3, success: true });
    expect(h.hub.calls).toHaveLength(1);
    expect(h.hub.calls[0]).toMatchObject({ domain: "lock", service: "unlock" });
    // ...and the state echo lands on the entity subscription (id 2) shortly after.
    await tick();
    const event = h.last("event") as { id: number; event: { a: Record<string, { s: string }> } };
    expect(event.id).toBe(2);
    expect(event.event.a["lock.front_door_lock"].s).toBe("unlocked");
  });

  it("echoes `jammed` (never the target) for a jammed outcome", async () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 2 });
    h.hub.setServiceOutcome({ domain: "lock", service: "lock", outcome: "jammed", delayMs: 0 });
    h.session.handleMessage({
      type: "call_service",
      id: 3,
      domain: "lock",
      service: "lock",
      target: { entity_id: "lock.front_door_lock" },
    });
    await tick();
    const event = h.last("event") as { event: { a: Record<string, { s: string }> } };
    expect(event.event.a["lock.front_door_lock"].s).toBe("jammed");
  });

  it("fails the result and emits no echo for a reject outcome", async () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 2 });
    h.hub.setServiceOutcome({ domain: "lock", service: "lock", outcome: "reject", delayMs: 0 });
    const before = h.sent.length;
    h.session.handleMessage({
      type: "call_service",
      id: 9,
      domain: "lock",
      service: "lock",
      target: { entity_id: "lock.front_door_lock" },
    });
    expect(h.last("result")).toMatchObject({ id: 9, success: false });
    await tick();
    // Only the failure result was sent — no event echo.
    const events = h.sent.slice(before).filter((m) => m.type === "event");
    expect(events).toHaveLength(0);
  });

  it("acks success but never echoes for a silent outcome", async () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 2 });
    h.hub.setServiceOutcome({ domain: "lock", service: "lock", outcome: "silent", delayMs: 0 });
    const before = h.sent.length;
    h.session.handleMessage({
      type: "call_service",
      id: 4,
      domain: "lock",
      service: "lock",
      target: { entity_id: "lock.front_door_lock" },
    });
    expect(h.last("result")).toMatchObject({ id: 4, success: true });
    await tick();
    const events = h.sent.slice(before).filter((m) => m.type === "event");
    expect(events).toHaveLength(0);
  });

  it("echoes the exact command id it received", () => {
    const h = authed();
    h.session.handleMessage({ type: "get_states", id: 123 });
    expect(h.last("result")).toMatchObject({ id: 123, success: true });
  });

  it("pushes control-API state changes over the live subscription", () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 2 });
    h.hub.pushState({ entity_id: "light.living_room", state: "on" });
    const event = h.last("event") as { id: number; event: { a: Record<string, { s: string }> } };
    expect(event.id).toBe(2);
    expect(event.event.a["light.living_room"].s).toBe("on");
  });

  it("a reset cancels in-flight delayed service echoes (no cross-scenario bleed)", async () => {
    const h = authed();
    h.session.handleMessage({ type: "subscribe_entities", id: 2 });
    // Schedule a delayed echo, then reset before it lands.
    h.hub.setServiceOutcome({ domain: "light", service: "turn_on", outcome: "confirm", delayMs: 5 });
    h.session.handleMessage({
      type: "call_service",
      id: 3,
      domain: "light",
      service: "turn_on",
      target: { entity_id: "light.living_room" },
    });
    h.hub.reset(getScenario("default"));
    await new Promise((r) => setTimeout(r, 15));
    h.session.handleMessage({ type: "get_states", id: 4 });
    const result = h.last("result") as { result: { entity_id: string; state: string }[] };
    expect(result.result.find((e) => e.entity_id === "light.living_room")?.state).toBe("off");
  });

  describe("camera/stream outcomes", () => {
    it("succeeds with the mock HLS url by default", () => {
      const h = authed();
      h.session.handleMessage({ type: "camera/stream", id: 9, entity_id: "camera.front_door" });
      expect(h.last("result")).toMatchObject({
        id: 9,
        success: true,
        result: { url: "/api/hls/mock/master.m3u8" },
      });
    });

    it("a scripted `error` outcome fails the command for that entity only", () => {
      const h = authed();
      h.hub.setStreamOutcome({ entity_id: "camera.front_gate_event", outcome: "error" });
      h.session.handleMessage({ type: "camera/stream", id: 10, entity_id: "camera.front_gate_event" });
      expect(h.last("result")).toMatchObject({
        id: 10,
        success: false,
        error: { code: "start_stream_failed" },
      });
      h.session.handleMessage({ type: "camera/stream", id: 11, entity_id: "camera.front_door" });
      expect(h.last("result")).toMatchObject({ id: 11, success: true });
    });

    it("a scripted `timeout` outcome never replies (the client's own bound steps down)", async () => {
      const h = authed();
      h.hub.setStreamOutcome({ entity_id: "camera.front_gate_event", outcome: "timeout" });
      const before = h.sent.length;
      h.session.handleMessage({ type: "camera/stream", id: 12, entity_id: "camera.front_gate_event" });
      await tick();
      expect(h.sent.slice(before)).toHaveLength(0);
    });

    it("a `default`-keyed outcome applies to any entity, and a reset restores ok", () => {
      const h = authed();
      h.hub.setStreamOutcome({ outcome: "error" });
      h.session.handleMessage({ type: "camera/stream", id: 13, entity_id: "camera.backyard" });
      expect(h.last("result")).toMatchObject({ id: 13, success: false });
      h.hub.reset(getScenario("default"));
      h.session.handleMessage({ type: "camera/stream", id: 14, entity_id: "camera.backyard" });
      expect(h.last("result")).toMatchObject({ id: 14, success: true });
    });
  });

  it("the ring-camera scenario carries the split entities + a populated event selector", () => {
    const h = authed("ring-camera");
    h.session.handleMessage({ type: "get_states", id: 20 });
    const result = h.last("result") as { result: { entity_id: string; attributes: Record<string, unknown> }[] };
    const ids = result.result.map((e) => e.entity_id);
    expect(ids).toEqual(
      expect.arrayContaining([
        "camera.front_gate_live",
        "camera.front_gate_snapshot",
        "camera.front_gate_event",
        "select.front_gate_event_select",
        "binary_sensor.front_gate_motion",
        "binary_sensor.front_gate_ding",
      ]),
    );
    const select = result.result.find((e) => e.entity_id === "select.front_gate_event_select")!;
    const options = select.attributes.options as string[];
    expect(options.length).toBeGreaterThanOrEqual(3);
    // Times must be parseable so the app plots events at their real times.
    for (const opt of options) {
      expect(Number.isFinite(Date.parse(opt.replace(/^[A-Za-z\s-]+/, "").trim()))).toBe(true);
    }
  });
});

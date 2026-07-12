/**
 * Tiny HTTP client for the mock server's `/__scenario/*` control API, imported by
 * the E2E specs so they read declaratively (reset, push state, script a service
 * outcome, drop the socket, read the call log). Mirrors the Android contract.
 */
import type { Outcome, ServiceCall } from "./wsProtocol";
import { MOCK_HA_PORT } from "./port";

export class MockControl {
  constructor(private base = `http://localhost:${MOCK_HA_PORT}`) {}

  private async post(path: string, body?: unknown): Promise<void> {
    const res = await fetch(`${this.base}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body ?? {}),
    });
    if (!res.ok) throw new Error(`mock control ${path} -> ${res.status}`);
  }

  /** Load a named scenario and push its full state to any connected client. */
  reset(scenario = "default"): Promise<void> {
    return this.post("/__scenario/reset", { scenario });
  }

  /** Push an arbitrary state change over the live entity subscription. */
  pushState(entity_id: string, state: string, attributes?: Record<string, unknown>): Promise<void> {
    return this.post("/__scenario/state", { entity_id, state, attributes });
  }

  /** Script how the next matching `call_service` resolves. */
  setServiceOutcome(input: {
    domain: string;
    service: string;
    entity_id?: string;
    outcome: Outcome;
    delayMs?: number;
    state?: string;
  }): Promise<void> {
    return this.post("/__scenario/service-outcome", input);
  }

  /** Close all live sockets; the app auto-reconnects. */
  disconnect(): Promise<void> {
    return this.post("/__scenario/disconnect");
  }

  /** The recorded `call_service` log, for round-trip assertions. */
  async getCalls(): Promise<ServiceCall[]> {
    const res = await fetch(`${this.base}/__scenario/calls`);
    return (await res.json()) as ServiceCall[];
  }

  /** Live + total connection counts, for deterministic reconnect assertions. */
  async stats(): Promise<{ connections: number; sessions: number }> {
    const res = await fetch(`${this.base}/__scenario/stats`);
    return (await res.json()) as { connections: number; sessions: number };
  }
}

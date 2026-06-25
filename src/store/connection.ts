import { createFixtureSource } from "./fixtureSource";
import { createHaSource } from "./haSource";
import { loadCredentials } from "./credentials";
import type { HistoryPoint, ServiceData, Source } from "./source";
import type { AutomationConfig } from "../lib/automations";

let current: Source | null = null;

/**
 * Pick the data source: live HA when credentials are saved, otherwise the demo
 * fixture source. The store sees the same shape either way.
 */
function selectSource(): Source {
  const creds = loadCredentials();
  return creds ? createHaSource(creds) : createFixtureSource();
}

/** (Re)start the active source — call on app mount and after connect/disconnect. */
export function startConnection(): void {
  current?.stop();
  current = selectSource();
  void current.start();
}

/** Tear down the active source (app unmount). */
export function stopConnection(): void {
  current?.stop();
  current = null;
}

/**
 * Perform an HA service call through the active source (fixture simulates it;
 * live HA forwards it and reconciles via the state echo). Throws if the source
 * can't perform writes (e.g. not connected).
 */
export function callService(
  domain: string,
  service: string,
  data?: ServiceData,
): Promise<void> {
  if (!current?.callService) {
    return Promise.reject(new Error("Not connected."));
  }
  return current.callService(domain, service, data);
}

/**
 * Fetch recent state history for an entity through the active source (live HA
 * over the WebSocket; fixtures synthesize it). Rejects if the source can't
 * provide history (e.g. not connected).
 */
export function fetchHistory(
  entityId: string,
  hours: number,
): Promise<HistoryPoint[]> {
  if (!current?.fetchHistory) {
    return Promise.reject(new Error("History unavailable."));
  }
  return current.fetchHistory(entityId, hours);
}

/**
 * Automation editor plumbing — read/write/delete HA automations through the
 * active source (live HA over the Config API; fixtures simulate in memory).
 * Reject if the source can't manage automations (e.g. not connected).
 */
export function getAutomationConfig(id: string): Promise<AutomationConfig | null> {
  if (!current?.getAutomationConfig) {
    return Promise.reject(new Error("Automations unavailable."));
  }
  return current.getAutomationConfig(id);
}

export function saveAutomationConfig(config: AutomationConfig): Promise<void> {
  if (!current?.saveAutomationConfig) {
    return Promise.reject(new Error("Automations unavailable."));
  }
  return current.saveAutomationConfig(config);
}

export function deleteAutomationConfig(id: string): Promise<void> {
  if (!current?.deleteAutomationConfig) {
    return Promise.reject(new Error("Automations unavailable."));
  }
  return current.deleteAutomationConfig(id);
}

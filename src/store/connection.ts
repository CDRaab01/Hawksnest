import { createFixtureSource } from "./fixtureSource";
import { createHaSource } from "./haSource";
import { loadCredentials } from "./credentials";
import type { Source } from "./source";

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

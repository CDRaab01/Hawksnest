import { createFixtureSource } from "./fixtureSource";
import { createHaSource, type HaCredentials } from "./haSource";
import type { Source } from "./source";

const CREDS_KEY = "hawksnest.ha";

/** Read saved HA credentials (Phase 1 will write these from the setup screen). */
function loadCredentials(): HaCredentials | null {
  try {
    const raw = localStorage.getItem(CREDS_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<HaCredentials>;
    if (parsed.url && parsed.token) return { url: parsed.url, token: parsed.token };
  } catch {
    // ignore malformed storage
  }
  return null;
}

/**
 * Pick the data source: live HA when credentials are saved, otherwise the demo
 * fixture source. The store sees the same shape either way.
 */
export function selectSource(): Source {
  const creds = loadCredentials();
  return creds ? createHaSource(creds) : createFixtureSource();
}

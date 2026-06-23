import type { HaCredentials } from "./haSource";

const CREDS_KEY = "hawksnest.ha";

/**
 * HA credentials persistence.
 *
 * Security note: the long-lived token is a bearer credential to the whole house
 * (including door locks). We persist it to localStorage as a deliberate v1
 * trade-off (it must survive reloads on a trusted personal device); the cost is
 * that it's readable by any XSS on the origin. The Phase 5 service worker MUST
 * NOT cache the token or any authenticated response. "Disconnect" clears it.
 */
export function loadCredentials(): HaCredentials | null {
  try {
    const raw = localStorage.getItem(CREDS_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<HaCredentials>;
    if (parsed.url && parsed.token) {
      return { url: parsed.url, token: parsed.token };
    }
  } catch {
    // ignore malformed storage
  }
  return null;
}

export function saveCredentials(creds: HaCredentials): void {
  localStorage.setItem(CREDS_KEY, JSON.stringify(creds));
}

export function clearCredentials(): void {
  localStorage.removeItem(CREDS_KEY);
}

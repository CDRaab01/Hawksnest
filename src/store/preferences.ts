const PREFS_KEY = "hawksnest.prefs";

/**
 * User personalization (Phase 3), persisted per-device to localStorage.
 *
 * Mirrors the `credentials.ts` pattern: a tolerant load + a plain save. Unlike
 * credentials this holds no secrets — just display preferences:
 *   - `favorites`: the ordered list of entity ids pinned to Home. When absent
 *     (never edited) the static seed in `config/favorites.ts` is used instead;
 *     an explicit empty array means "the user unpinned everything".
 *   - `hidden`: entity ids hidden from the area views (Home hub + Area detail).
 */
export interface Preferences {
  favorites: string[];
  hidden: string[];
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => typeof v === "string");
}

export function loadPreferences(): Preferences | null {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<Preferences>;
    if (isStringArray(parsed.favorites) && isStringArray(parsed.hidden)) {
      return { favorites: parsed.favorites, hidden: parsed.hidden };
    }
  } catch {
    // ignore malformed storage
  }
  return null;
}

export function savePreferences(prefs: Preferences): void {
  localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
}

export function clearPreferences(): void {
  localStorage.removeItem(PREFS_KEY);
}

import { create } from "zustand";
import { favorites as favoritesSeed } from "../config/favorites";
import {
  clearPreferences,
  loadPreferences,
  savePreferences,
} from "./preferences";

/**
 * Personalization store (Phase 3) — the user-editable pins / hides that the
 * Customize screen drives and Home / Area screens read.
 *
 * Mirrors `entityStore.ts` (plain Zustand + selector hooks). Every mutating
 * action writes through to localStorage via `savePreferences`, matching the
 * manual persistence convention in `credentials.ts` (no middleware).
 *
 * `favorites === null` means "never customized — use the static seed in
 * config/favorites.ts". The first edit materializes that seed into a concrete
 * list so subsequent reloads honor the user's choices.
 */
interface PrefsState {
  favorites: string[] | null;
  hidden: string[];
  togglePin: (id: string) => void;
  toggleHidden: (id: string) => void;
  /** Move a pinned entity up (-1) or down (+1); clamps at the ends. */
  moveFavorite: (id: string, dir: -1 | 1) => void;
  /** Reorder a pinned entity from one index to another (drag-and-drop). */
  reorderFavorites: (from: number, to: number) => void;
  /** Forget all customizations and fall back to defaults. */
  resetAll: () => void;
}

const initial = loadPreferences();

/** The effective favorites list given the (possibly null) stored value. */
function effectiveFavorites(favorites: string[] | null): string[] {
  return favorites ?? favoritesSeed;
}

function persist(favorites: string[] | null, hidden: string[]): void {
  savePreferences({ favorites: effectiveFavorites(favorites), hidden });
}

export const usePrefsStore = create<PrefsState>((set, get) => ({
  favorites: initial ? initial.favorites : null,
  hidden: initial?.hidden ?? [],

  togglePin: (id) => {
    const base = [...effectiveFavorites(get().favorites)];
    const next = base.includes(id)
      ? base.filter((x) => x !== id)
      : [...base, id];
    persist(next, get().hidden);
    set({ favorites: next });
  },

  toggleHidden: (id) => {
    const hidden = get().hidden.includes(id)
      ? get().hidden.filter((x) => x !== id)
      : [...get().hidden, id];
    persist(get().favorites, hidden);
    set({ hidden });
  },

  moveFavorite: (id, dir) => {
    const list = [...effectiveFavorites(get().favorites)];
    const i = list.indexOf(id);
    const j = i + dir;
    if (i === -1 || j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    persist(list, get().hidden);
    set({ favorites: list });
  },

  reorderFavorites: (from, to) => {
    const list = [...effectiveFavorites(get().favorites)];
    if (
      from === to ||
      from < 0 ||
      to < 0 ||
      from >= list.length ||
      to >= list.length
    ) {
      return;
    }
    const [moved] = list.splice(from, 1);
    list.splice(to, 0, moved);
    persist(list, get().hidden);
    set({ favorites: list });
  },

  resetAll: () => {
    clearPreferences();
    set({ favorites: null, hidden: [] });
  },
}));

// --- selector hooks (subscribe to slices; return stable refs) ---

/** Ordered pinned entity ids — the user list if set, else the static seed. */
export const useFavorites = (): string[] =>
  usePrefsStore((s) => effectiveFavorites(s.favorites));

/** Entity ids hidden from the area views. */
export const useHidden = (): string[] => usePrefsStore((s) => s.hidden);

export const useIsPinned = (id: string): boolean =>
  usePrefsStore((s) => effectiveFavorites(s.favorites).includes(id));

export const useIsHidden = (id: string): boolean =>
  usePrefsStore((s) => s.hidden.includes(id));

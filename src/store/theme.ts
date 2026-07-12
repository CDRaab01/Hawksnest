import { create } from "zustand";

/**
 * Appearance preference. Dark is the product default (a wall-mounted OLED
 * dashboard); Light is opt-in; System follows the OS. Persisted to localStorage
 * under the same manual-write convention as credentials/preferences (no
 * middleware). The class swap on <html> is what actually flips the token block
 * in theme/tokens.css — Tailwind darkMode:"class" keys off `.dark`, and
 * `:root.light` provides the light overrides.
 *
 * An inline script in index.html applies the saved preference before first
 * paint (no flash); this store keeps it in sync at runtime and reacts to OS
 * changes while on "System".
 */
export type ThemePref = "dark" | "light" | "system";
export type ResolvedTheme = "dark" | "light";

const STORAGE_KEY = "hawksnest:theme";
const META_COLOR: Record<ResolvedTheme, string> = {
  dark: "#0B0D10",
  light: "#eef1f5",
};

export function loadThemePref(): ThemePref {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === "light" || v === "dark" || v === "system") return v;
  } catch {
    // Private mode / disabled storage — fall through to the default.
  }
  return "dark";
}

function systemPrefersLight(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-color-scheme: light)").matches
  );
}

export function resolveTheme(pref: ThemePref): ResolvedTheme {
  if (pref === "system") return systemPrefersLight() ? "light" : "dark";
  return pref;
}

/** Flip the <html> classes and the browser theme-color to a resolved theme. */
export function applyResolvedTheme(resolved: ResolvedTheme): void {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  root.classList.toggle("light", resolved === "light");
  root.classList.toggle("dark", resolved === "dark");
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", META_COLOR[resolved]);
}

interface ThemeState {
  pref: ThemePref;
  /** The theme actually in effect (pref resolved through the OS setting). */
  resolved: ResolvedTheme;
  setPref: (pref: ThemePref) => void;
  /** Re-resolve after an OS change; only meaningful while pref === "system". */
  syncSystem: () => void;
}

const initialPref = loadThemePref();

export const useThemeStore = create<ThemeState>((set, get) => ({
  pref: initialPref,
  resolved: resolveTheme(initialPref),

  setPref: (pref) => {
    try {
      localStorage.setItem(STORAGE_KEY, pref);
    } catch {
      // Non-fatal: the choice just won't persist across reloads.
    }
    const resolved = resolveTheme(pref);
    applyResolvedTheme(resolved);
    set({ pref, resolved });
  },

  syncSystem: () => {
    if (get().pref !== "system") return;
    const resolved = resolveTheme("system");
    applyResolvedTheme(resolved);
    set({ resolved });
  },
}));

/**
 * Apply the stored theme and start reacting to OS changes. Idempotent-ish; call
 * once at startup. The inline index.html script already set the class for first
 * paint, so this mainly wires the live listener and keeps the store honest.
 */
export function initTheme(): void {
  applyResolvedTheme(useThemeStore.getState().resolved);
  if (typeof window !== "undefined" && typeof window.matchMedia === "function") {
    window
      .matchMedia("(prefers-color-scheme: light)")
      .addEventListener("change", () => useThemeStore.getState().syncSystem());
  }
}

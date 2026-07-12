import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  loadThemePref,
  resolveTheme,
  applyResolvedTheme,
  useThemeStore,
} from "../theme";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.className = "dark";
  // Reset the meta tag the store writes to.
  document.head.innerHTML = '<meta name="theme-color" content="#0B0D10" />';
});

describe("theme preference", () => {
  it("defaults to dark when nothing is stored", () => {
    expect(loadThemePref()).toBe("dark");
  });

  it("reads a valid stored preference and ignores junk", () => {
    localStorage.setItem("hawksnest:theme", "light");
    expect(loadThemePref()).toBe("light");
    localStorage.setItem("hawksnest:theme", "banana");
    expect(loadThemePref()).toBe("dark");
  });

  it("resolves explicit prefs without consulting the OS", () => {
    expect(resolveTheme("dark")).toBe("dark");
    expect(resolveTheme("light")).toBe("light");
  });

  it("resolves system via matchMedia", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({ matches: true })),
    );
    expect(resolveTheme("system")).toBe("light");
    vi.unstubAllGlobals();
  });
});

describe("applyResolvedTheme", () => {
  it("light swaps the html class and theme-color", () => {
    applyResolvedTheme("light");
    const root = document.documentElement;
    expect(root.classList.contains("light")).toBe(true);
    expect(root.classList.contains("dark")).toBe(false);
    expect(
      document.querySelector('meta[name="theme-color"]')?.getAttribute("content"),
    ).toBe("#eef1f5");
  });

  it("dark restores the class and theme-color", () => {
    applyResolvedTheme("light");
    applyResolvedTheme("dark");
    const root = document.documentElement;
    expect(root.classList.contains("dark")).toBe(true);
    expect(root.classList.contains("light")).toBe(false);
    expect(
      document.querySelector('meta[name="theme-color"]')?.getAttribute("content"),
    ).toBe("#0B0D10");
  });
});

describe("useThemeStore.setPref", () => {
  it("persists and applies the chosen theme", () => {
    useThemeStore.getState().setPref("light");
    expect(localStorage.getItem("hawksnest:theme")).toBe("light");
    expect(useThemeStore.getState().resolved).toBe("light");
    expect(document.documentElement.classList.contains("light")).toBe(true);

    useThemeStore.getState().setPref("dark");
    expect(useThemeStore.getState().resolved).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });
});

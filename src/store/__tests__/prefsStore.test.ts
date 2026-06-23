import { describe, it, expect, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { favorites as seed } from "../../config/favorites";
import { loadPreferences } from "../preferences";
import { usePrefsStore, useFavorites } from "../prefsStore";

beforeEach(() => {
  localStorage.clear();
  usePrefsStore.setState({ favorites: null, hidden: [] });
});

describe("togglePin", () => {
  it("materializes the seed then appends a new pin", () => {
    usePrefsStore.getState().togglePin("light.basement");
    const fav = usePrefsStore.getState().favorites;
    expect(fav).toEqual([...seed, "light.basement"]);
    // persisted through to localStorage
    expect(loadPreferences()?.favorites).toEqual([...seed, "light.basement"]);
  });

  it("unpins an already-pinned (seeded) entity", () => {
    usePrefsStore.getState().togglePin("lock.back_door_lock");
    expect(usePrefsStore.getState().favorites).toEqual(
      seed.filter((id) => id !== "lock.back_door_lock"),
    );
  });
});

describe("moveFavorite", () => {
  it("swaps with the neighbor in the given direction", () => {
    usePrefsStore.getState().moveFavorite(seed[0], 1);
    expect(usePrefsStore.getState().favorites).toEqual([
      seed[1],
      seed[0],
      seed[2],
    ]);
  });

  it("clamps at the ends (no-op past the boundary)", () => {
    // A boundary move is a no-op and doesn't even materialize the seed.
    usePrefsStore.getState().moveFavorite(seed[0], -1);
    expect(usePrefsStore.getState().favorites).toBeNull();
    usePrefsStore.getState().moveFavorite(seed[seed.length - 1], 1);
    expect(usePrefsStore.getState().favorites).toBeNull();
  });
});

describe("reorderFavorites", () => {
  it("moves an item from one index to another and persists", () => {
    usePrefsStore.getState().reorderFavorites(0, 2);
    expect(usePrefsStore.getState().favorites).toEqual([
      seed[1],
      seed[2],
      seed[0],
    ]);
    expect(loadPreferences()?.favorites).toEqual([seed[1], seed[2], seed[0]]);
  });

  it("is a no-op for an unchanged or out-of-range move", () => {
    usePrefsStore.getState().reorderFavorites(1, 1);
    expect(usePrefsStore.getState().favorites).toBeNull();
    usePrefsStore.getState().reorderFavorites(0, 99);
    expect(usePrefsStore.getState().favorites).toBeNull();
  });
});

describe("toggleHidden", () => {
  it("round-trips an id in and out of the hidden set", () => {
    usePrefsStore.getState().toggleHidden("binary_sensor.front_door_intrusion");
    expect(usePrefsStore.getState().hidden).toEqual([
      "binary_sensor.front_door_intrusion",
    ]);
    usePrefsStore.getState().toggleHidden("binary_sensor.front_door_intrusion");
    expect(usePrefsStore.getState().hidden).toEqual([]);
  });
});

describe("persistence", () => {
  it("survives a reload (re-init from localStorage)", () => {
    usePrefsStore.getState().togglePin("light.basement");
    usePrefsStore.getState().toggleHidden("camera.front_door");
    const reloaded = loadPreferences();
    expect(reloaded).toEqual({
      favorites: [...seed, "light.basement"],
      hidden: ["camera.front_door"],
    });
  });
});

describe("resetAll", () => {
  it("clears storage and falls back to the seed", () => {
    usePrefsStore.getState().togglePin("light.basement");
    usePrefsStore.getState().toggleHidden("camera.front_door");
    usePrefsStore.getState().resetAll();
    expect(usePrefsStore.getState().favorites).toBeNull();
    expect(usePrefsStore.getState().hidden).toEqual([]);
    expect(loadPreferences()).toBeNull();
  });
});

describe("useFavorites", () => {
  it("returns the seed when never customized, the user list once set", () => {
    const { result, rerender } = renderHook(() => useFavorites());
    expect(result.current).toEqual(seed);
    usePrefsStore.getState().togglePin("light.basement");
    rerender();
    expect(result.current).toEqual([...seed, "light.basement"]);
  });
});

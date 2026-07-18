import { describe, it, expect, vi, afterEach } from "vitest";
import type { HassEntity } from "../ha";
import {
  GRACE_WINDOW_MS,
  formatAsOf,
  maskSecurityStates,
  offlinePhase,
  probeHaReachability,
} from "../offline";

const entity = (id: string, state: string): HassEntity =>
  ({ entity_id: id, state, attributes: {} }) as HassEntity;

describe("offlinePhase", () => {
  const t0 = 1_700_000_000_000;

  it("is live while connected or in demo, whatever the stale clock says", () => {
    expect(offlinePhase("connected", undefined, t0)).toBe("live");
    expect(offlinePhase("connected", t0 - 999_999, t0)).toBe("live");
    expect(offlinePhase("demo", undefined, t0)).toBe("live");
  });

  it("stays live on a first-ever connect (nothing stale to show)", () => {
    expect(offlinePhase("connecting", undefined, t0)).toBe("live");
  });

  it("holds the grace window for just under 120s of an in-session drop", () => {
    expect(offlinePhase("connecting", t0, t0)).toBe("grace");
    expect(offlinePhase("connecting", t0, t0 + GRACE_WINDOW_MS - 1)).toBe("grace");
  });

  it("collapses to offline once the window expires", () => {
    expect(offlinePhase("connecting", t0, t0 + GRACE_WINDOW_MS)).toBe("offline");
    expect(offlinePhase("connecting", t0, t0 + GRACE_WINDOW_MS + 1)).toBe("offline");
  });

  it("collapses to offline immediately on a terminal error", () => {
    expect(offlinePhase("error", undefined, t0)).toBe("offline");
    expect(offlinePhase("error", t0, t0)).toBe("offline");
  });
});

describe("formatAsOf", () => {
  // Local-time construction so the assertion is TZ-independent.
  const at = (y: number, mo: number, d: number, h: number, mi: number) =>
    new Date(y, mo, d, h, mi).getTime();

  it("renders clock-only for a same-day timestamp", () => {
    const then = at(2026, 6, 17, 15, 42);
    const now = at(2026, 6, 17, 18, 0);
    expect(formatAsOf(then, now)).toBe("3:42 PM");
  });

  it("carries the date once it's no longer today", () => {
    const then = at(2026, 6, 16, 9, 5);
    const now = at(2026, 6, 17, 8, 0);
    expect(formatAsOf(then, now)).toBe("Jul 16, 9:05 AM");
  });

  it("handles midnight and noon without a 0 o'clock", () => {
    const midnight = at(2026, 6, 17, 0, 0);
    const noon = at(2026, 6, 17, 12, 0);
    const now = at(2026, 6, 17, 23, 0);
    expect(formatAsOf(midnight, now)).toBe("12:00 AM");
    expect(formatAsOf(noon, now)).toBe("12:00 PM");
  });
});

describe("maskSecurityStates — the never-render-a-stale-lock invariant", () => {
  it("collapses lock and alarm states to unavailable, leaves everything else", () => {
    const masked = maskSecurityStates({
      "lock.front": entity("lock.front", "locked"),
      "alarm_control_panel.home": entity("alarm_control_panel.home", "armed_away"),
      "light.porch": entity("light.porch", "on"),
    });
    expect(masked["lock.front"].state).toBe("unavailable");
    expect(masked["alarm_control_panel.home"].state).toBe("unavailable");
    expect(masked["light.porch"].state).toBe("on");
  });

  it("preserves attributes (names keep resolving on the masked card)", () => {
    const masked = maskSecurityStates({
      "lock.front": {
        entity_id: "lock.front",
        state: "locked",
        attributes: { friendly_name: "Front Door" },
      } as HassEntity,
    });
    expect(masked["lock.front"].attributes.friendly_name).toBe("Front Door");
  });

  it("returns the same object when nothing needs masking", () => {
    const entities = {
      "light.porch": entity("light.porch", "on"),
      "lock.front": entity("lock.front", "unavailable"), // already honest
    };
    expect(maskSecurityStates(entities)).toBe(entities);
  });
});

describe("probeHaReachability", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("treats ANY http response as the server answering (even 401 from HA)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("auth", { status: 401 })));
    expect(await probeHaReachability("")).toBe("ha-not-answering");
  });

  it("treats a transport failure as the network being unreachable", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    }));
    expect(await probeHaReachability("")).toBe("network-unreachable");
  });

  it("probes /api/ against the configured base URL", async () => {
    const fetchMock = vi.fn(async (_url: string) => new Response("", { status: 404 }));
    vi.stubGlobal("fetch", fetchMock);
    await probeHaReachability("http://ha.local:8123/");
    expect(fetchMock.mock.calls[0][0]).toBe("http://ha.local:8123/api/");
  });
});

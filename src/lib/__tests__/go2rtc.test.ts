import { describe, it, expect, vi, afterEach } from "vitest";

// Fresh module per test so the module-level caches (streams, circuit-breaker)
// don't leak between cases.
async function freshModule() {
  vi.resetModules();
  return import("../go2rtc");
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("go2rtc helper", () => {
  it("builds a same-origin ws signaling url (encoded)", async () => {
    const { go2rtcWsUrl } = await freshModule();
    expect(go2rtcWsUrl("front door")).toBe(
      `ws://${window.location.host}/go2rtc/api/ws?src=front%20door`,
    );
  });

  it("is optimistic before the stream list is known, then honors the circuit-breaker", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve({ ok: false, json: () => Promise.resolve({}) })),
    );
    const { go2rtcMaybeAvailable, reportGo2rtcMedia } = await freshModule();

    // Stream list not resolved yet → assume available.
    expect(go2rtcMaybeAvailable("front_door")).toBe(true);
    // First media failure trips the breaker for the rest of the session.
    reportGo2rtcMedia(false);
    expect(go2rtcMaybeAvailable("front_door")).toBe(false);
    // A success clears it.
    reportGo2rtcMedia(true);
    expect(go2rtcMaybeAvailable("front_door")).toBe(true);
  });

  it("skips a camera go2rtc isn't serving once the list is known", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve({ ok: true, json: () => Promise.resolve({ front_door: {}, kitchen: {} }) }),
      ),
    );
    const mod = await freshModule();
    mod.primeGo2rtcStreams();
    await new Promise((r) => setTimeout(r, 0)); // let the fetch chain settle

    expect(mod.go2rtcMaybeAvailable("front_door")).toBe(true);
    expect(mod.go2rtcMaybeAvailable("garage")).toBe(false); // not in go2rtc
  });

  it("skips the go2rtc tier when the stream list can't be fetched (go2rtc down)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new Error("go2rtc unreachable"))),
    );
    const mod = await freshModule();
    mod.primeGo2rtcStreams();
    await new Promise((r) => setTimeout(r, 0));
    // A failed fetch caches an empty set: go2rtc is unreachable, so don't attempt
    // it (that would just cost the 8s watchdog) — go straight to the HA path.
    // Self-heals after the 60s TTL re-fetches.
    expect(mod.go2rtcMaybeAvailable("front_door")).toBe(false);
  });
});

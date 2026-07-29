import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { frigateHasCamera, primeFrigateCameras, resetFrigateCamerasCache } from "../frigate";

/** Stub `/api/frigate/config` with a response, or make the fetch reject. */
function stubConfig(body: unknown, ok = true) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok,
    json: async () => body,
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  resetFrigateCamerasCache();
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetFrigateCamerasCache();
});

describe("frigateHasCamera", () => {
  it("is false before the config has been fetched", () => {
    stubConfig({ cameras: { bedroom: {} } });
    // Deliberately not primed — an un-fetched cache must not claim a camera.
    expect(frigateHasCamera("bedroom")).toBe(false);
  });

  it("reports cameras named by the config once primed", async () => {
    stubConfig({ cameras: { bedroom: {}, front_door: {} } });
    await primeFrigateCameras();
    expect(frigateHasCamera("bedroom")).toBe(true);
    expect(frigateHasCamera("front_door")).toBe(true);
  });

  it("does not claim a camera Frigate omits", async () => {
    stubConfig({ cameras: { bedroom: {} } });
    await primeFrigateCameras();
    expect(frigateHasCamera("garage")).toBe(false);
  });

  // The whole point of failing closed: with no Frigate deployed, every camera must
  // read exactly as it did before Frigate existed, not flip to a broken NVR path.
  it("fails closed when the config request errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network")));
    await primeFrigateCameras();
    expect(frigateHasCamera("bedroom")).toBe(false);
  });

  it("fails closed on a non-ok response", async () => {
    stubConfig({ cameras: { bedroom: {} } }, false);
    await primeFrigateCameras();
    expect(frigateHasCamera("bedroom")).toBe(false);
  });

  // A dev server (or a misrouted prefix) answers with the SPA's index.html at 200.
  // That parses as neither an object with `cameras` nor valid JSON — either way it
  // must not be read as "Frigate serves every camera".
  it("fails closed when the response has no cameras map", async () => {
    stubConfig({ some: "other json" });
    await primeFrigateCameras();
    expect(frigateHasCamera("bedroom")).toBe(false);
  });
});

describe("primeFrigateCameras", () => {
  it("fetches once and serves later calls from cache", async () => {
    const fetchMock = stubConfig({ cameras: { bedroom: {} } });
    await primeFrigateCameras();
    await primeFrigateCameras();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("shares one in-flight request between concurrent callers", async () => {
    const fetchMock = stubConfig({ cameras: { bedroom: {} } });
    await Promise.all([primeFrigateCameras(), primeFrigateCameras(), primeFrigateCameras()]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(frigateHasCamera("bedroom")).toBe(true);
  });
});

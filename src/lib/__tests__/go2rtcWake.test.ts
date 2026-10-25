import { describe, it, expect } from "vitest";
import {
  CONNECT_WATCHDOG_MS,
  WAKE_BUDGET_MS,
  WAKE_RETRY_MS,
  go2rtcWatchdogMs,
  sourceErrorRetryDelayMs,
} from "../go2rtcWake";

describe("go2rtcWake", () => {
  it("steps an awake camera down on a source error, never retries", () => {
    expect(sourceErrorRetryDelayMs(false, 0)).toBeNull();
    expect(sourceErrorRetryDelayMs(false, 500)).toBeNull();
  });

  it("retries a sleeping camera every second inside the wake budget", () => {
    expect(sourceErrorRetryDelayMs(true, 0)).toBe(WAKE_RETRY_MS);
    expect(sourceErrorRetryDelayMs(true, 15_000)).toBe(WAKE_RETRY_MS);
    expect(sourceErrorRetryDelayMs(true, 28_999)).toBe(WAKE_RETRY_MS);
  });

  it("gives up once the next attempt would land past the budget", () => {
    expect(sourceErrorRetryDelayMs(true, 29_000)).toBeNull();
    expect(sourceErrorRetryDelayMs(true, 45_000)).toBeNull();
  });

  it("treats a negative elapsed (clock step) as zero", () => {
    expect(sourceErrorRetryDelayMs(true, -5_000)).toBe(WAKE_RETRY_MS);
    expect(go2rtcWatchdogMs(true, -5_000)).toBe(WAKE_BUDGET_MS);
  });

  it("always gives an awake camera the short leash", () => {
    expect(go2rtcWatchdogMs(false, 0)).toBe(CONNECT_WATCHDOG_MS);
    expect(go2rtcWatchdogMs(false, 20_000)).toBe(CONNECT_WATCHDOG_MS);
  });

  it("shares the budget across a sleeping camera's attempts, never below the short leash", () => {
    expect(go2rtcWatchdogMs(true, 0)).toBe(30_000);
    expect(go2rtcWatchdogMs(true, 12_000)).toBe(18_000);
    expect(go2rtcWatchdogMs(true, 25_000)).toBe(CONNECT_WATCHDOG_MS);
    expect(go2rtcWatchdogMs(true, 60_000)).toBe(CONNECT_WATCHDOG_MS);
  });

  it("keeps the constants the Kotlin twin pins", () => {
    expect(WAKE_RETRY_MS).toBe(1_000);
    expect(WAKE_BUDGET_MS).toBe(30_000);
    expect(CONNECT_WATCHDOG_MS).toBe(8_000);
  });
});

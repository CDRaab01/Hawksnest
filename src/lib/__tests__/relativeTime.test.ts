import { describe, it, expect } from "vitest";
import { relativeTime, parseHaTime } from "../relativeTime";

const NOW = 1_700_000_000_000;

describe("relativeTime", () => {
  it("collapses the last few seconds to 'now'", () => {
    expect(relativeTime(NOW - 2_000, NOW)).toBe("now");
  });

  it("renders seconds / minutes / hours / days / weeks", () => {
    expect(relativeTime(NOW - 30_000, NOW)).toBe("30s ago");
    expect(relativeTime(NOW - 5 * 60_000, NOW)).toBe("5m ago");
    expect(relativeTime(NOW - 3 * 3_600_000, NOW)).toBe("3h ago");
    expect(relativeTime(NOW - 2 * 86_400_000, NOW)).toBe("2d ago");
    expect(relativeTime(NOW - 14 * 86_400_000, NOW)).toBe("2w ago");
  });

  it("clamps future timestamps to 'now'", () => {
    expect(relativeTime(NOW + 10_000, NOW)).toBe("now");
  });
});

describe("parseHaTime", () => {
  it("parses an ISO-8601 string", () => {
    expect(parseHaTime("2023-11-14T22:13:20.000Z")).toBe(Date.parse("2023-11-14T22:13:20.000Z"));
  });

  it("parses HA's compressed epoch-seconds form (numeric string and number)", () => {
    expect(parseHaTime("1700000000")).toBe(1_700_000_000_000);
    expect(parseHaTime(1_700_000_000)).toBe(1_700_000_000_000);
  });

  it("returns null for empty / unparseable input", () => {
    expect(parseHaTime(undefined)).toBeNull();
    expect(parseHaTime("")).toBeNull();
    expect(parseHaTime("not-a-date")).toBeNull();
  });
});

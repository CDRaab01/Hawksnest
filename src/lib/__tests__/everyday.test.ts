import { describe, it, expect, vi, afterEach } from "vitest";
import {
  cameraLabel,
  dayLabel,
  describeStatus,
  fetchDays,
  localDate,
  pickOf,
  setPick,
  streak,
  type EverydayDay,
  type EverydaySummary,
} from "../everyday";

const summary = (date: string, pick = true): EverydaySummary => ({
  date,
  status: "final",
  manual: false,
  candidateCount: 5,
  pick: pick
    ? ({ eventId: date, camera: "kitchen", clip: "", thumb: "" } as EverydaySummary["pick"])
    : null,
});

afterEach(() => vi.unstubAllGlobals());

describe("everyday helpers", () => {
  it("streak counts consecutive picked days back from the newest", () => {
    expect(streak([summary("2026-10-26"), summary("2026-10-25"), summary("2026-10-24")])).toBe(3);
    expect(streak([summary("2026-10-26"), summary("2026-10-24")])).toBe(1); // gap
    expect(streak([summary("2026-10-26", false), summary("2026-10-25")])).toBe(0);
    expect(streak([summary("2026-11-01"), summary("2026-10-31")])).toBe(2); // month edge
    expect(streak([])).toBe(0);
  });

  it("dayLabel parses as a local date, not UTC", () => {
    expect(dayLabel("2026-10-26", "en-US")).toBe("Mon, Oct 26");
  });

  it("localDate pads", () => {
    expect(localDate(new Date(2026, 0, 5))).toBe("2026-01-05");
  });

  it("cameraLabel", () => {
    expect(cameraLabel("front_door_reolink")).toBe("Front door reolink");
  });

  it("pickOf finds the picked candidate or null", () => {
    const day = { pick: "b", candidates: [{ eventId: "a" }, { eventId: "b" }] } as EverydayDay;
    expect(pickOf(day)?.eventId).toBe("b");
    expect(pickOf({ ...day, pick: null })).toBeNull();
  });

  it("describeStatus", () => {
    expect(describeStatus(401)).toMatch(/token/);
    expect(describeStatus(502)).toMatch(/isn't running/);
  });
});

describe("everyday client", () => {
  it("sends the HA token on reads and writes, same-origin under /everyday", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ days: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await fetchDays("tok");
    await setPick("tok", "2026-10-26", "e1");
    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls[0][0]).toBe("/everyday/api/days?limit=30");
    expect((calls[0][1].headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(calls[1][0]).toBe("/everyday/api/days/2026-10-26/pick");
    expect(calls[1][1].method).toBe("POST");
    expect(JSON.parse(calls[1][1].body as string)).toEqual({ eventId: "e1" });
  });

  it("throws a readable error on failure", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 502 })));
    await expect(fetchDays("tok")).rejects.toThrow(/isn't running/);
  });
});

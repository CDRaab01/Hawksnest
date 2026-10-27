import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { EverydayScreen } from "../EverydayScreen";
import { saveCredentials } from "../../store/credentials";

const cand = (id: string, camera: string, peak: number) => ({
  eventId: id,
  camera,
  label: "person",
  subLabel: null,
  start: peak - 2,
  end: peak + 20,
  peak,
  description: id === "a" ? "Two kids building a fort" : "",
  heuristic: 1,
  aiScore: 8,
  final: 0.9,
  reasons: [],
  clip: `media/candidates/2026-10-26/${id}.mp4`,
  thumb: `media/candidates/2026-10-26/${id}.jpg`,
});

function dayState(pick: string, manual: boolean) {
  return {
    date: "2026-10-26",
    status: "final",
    scannedAt: 0,
    eventCount: 42,
    genai: true,
    candidates: [cand("a", "big_room", 1793041200), cand("b", "kitchen", 1793044800)],
    pick,
    manual,
  };
}

let current = dayState("a", false);
const posts: { url: string; body: unknown }[] = [];

beforeEach(() => {
  localStorage.clear();
  posts.length = 0;
  current = dayState("a", false);
  saveCredentials({ url: "http://ha", token: "tok" });
  vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: () => "blob:x", revokeObjectURL: () => {} }));
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200 });
      if (init?.method === "POST") {
        const body = JSON.parse(String(init.body));
        posts.push({ url, body });
        current = dayState(body.eventId, true);
        return json(current);
      }
      if (url.startsWith("/everyday/api/days?"))
        return json({
          days: [{ date: "2026-10-26", status: "final", manual: current.manual, candidateCount: 2, pick: cand(current.pick, "x", 0) }],
        });
      if (url === "/everyday/api/days/2026-10-26") return json(current);
      if (url === "/everyday/api/reel") return json({ reels: [{ year: 2026, days: 1, url: "media/reel/everyday-2026.mp4", updatedAt: 0 }] });
      if (url.startsWith("/everyday/media/")) return new Response(new Blob(["x"]), { status: 200 });
      return new Response("", { status: 404 });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

function renderScreen() {
  return render(
    <MemoryRouter>
      <EverydayScreen />
    </MemoryRouter>,
  );
}

describe("EverydayScreen", () => {
  it("asks to connect when there is no HA token", () => {
    localStorage.clear();
    renderScreen();
    expect(screen.getByText(/Connect to Home Assistant/)).toBeInTheDocument();
  });

  it("shows the auto pick, lets you preview and swap it", async () => {
    renderScreen();
    expect(await screen.findByText("Auto pick")).toBeInTheDocument();
    expect(screen.getByText("Two kids building a fort")).toBeInTheDocument();
    expect(screen.getByText("2026")).toBeInTheDocument(); // reel row

    await userEvent.click(screen.getByRole("button", { name: /Preview Kitchen/ }));
    await userEvent.click(await screen.findByRole("button", { name: "Use this one" }));

    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({ url: "/everyday/api/days/2026-10-26/pick", body: { eventId: "b" } });
    // Both the player badge and the refreshed day chip now say so.
    await waitFor(() => expect(screen.getAllByText("Your pick")).toHaveLength(2));
    expect(screen.queryByRole("button", { name: "Use this one" })).toBeNull();
  });
});

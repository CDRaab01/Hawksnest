/**
 * The `everyday` service (sibling repo `hawksnest-automation`, nginx `/everyday/`) picks ~1.5 s
 * of Frigate footage per day for a "one second every day" family video. This module is the
 * client: types, the authenticated fetches, and the pure helpers the review screen uses.
 *
 * Auth: unlike `/ring-timeline/`, nginx gates `/everyday/` behind an HA-token `auth_request`,
 * because it serves interior-camera footage of the family. `<video src>`/`<img src>` can't
 * send `Authorization`, so media is fetched with the token and handed to the element as a blob
 * URL. Clips are ~0.5 MB; the yearly reel is the one large fetch and only on explicit download.
 */
const BASE = "/everyday";

export interface EverydayCandidate {
  eventId: string;
  camera: string;
  label: string;
  subLabel: string | null;
  /** Epoch seconds (Frigate's unit). */
  start: number;
  end: number;
  peak: number;
  description: string;
  heuristic: number;
  aiScore: number | null;
  final: number;
  reasons: string[];
  /** Relative to BASE, e.g. `media/candidates/2026-10-26/<id>.mp4`. */
  clip: string;
  thumb: string;
}

export interface EverydayDay {
  date: string;
  status: "provisional" | "final";
  scannedAt: number;
  eventCount: number;
  genai: boolean;
  candidates: EverydayCandidate[];
  pick: string | null;
  manual: boolean;
}

export interface EverydaySummary {
  date: string;
  status: EverydayDay["status"];
  manual: boolean;
  candidateCount: number;
  pick: EverydayCandidate | null;
}

export interface EverydayReel {
  year: number;
  days: number;
  url: string;
  updatedAt: number;
}

export class EverydayError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

function headers(token: string, json = false): HeadersInit {
  return json
    ? { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }
    : { Authorization: `Bearer ${token}` };
}

async function getJson<T>(path: string, token: string): Promise<T> {
  const res = await fetch(`${BASE}/${path}`, { headers: headers(token), cache: "no-store" });
  if (!res.ok) throw new EverydayError(describeStatus(res.status), res.status);
  return (await res.json()) as T;
}

/** Human copy for the failure states the screen can hit. */
export function describeStatus(status: number): string {
  if (status === 401 || status === 403) return "Home Assistant rejected the token.";
  if (status === 404) return "Not scanned yet.";
  if (status === 502 || status === 503 || status === 504) return "The everyday service isn't running.";
  return `Request failed (${status}).`;
}

export async function fetchDays(token: string, limit = 30): Promise<EverydaySummary[]> {
  return (await getJson<{ days: EverydaySummary[] }>(`api/days?limit=${limit}`, token)).days;
}

export function fetchDay(token: string, date: string): Promise<EverydayDay> {
  return getJson<EverydayDay>(`api/days/${date}`, token);
}

export async function fetchReels(token: string): Promise<EverydayReel[]> {
  return (await getJson<{ reels: EverydayReel[] }>("api/reel", token)).reels;
}

export async function setPick(token: string, date: string, eventId: string): Promise<EverydayDay> {
  const res = await fetch(`${BASE}/api/days/${date}/pick`, {
    method: "POST",
    headers: headers(token, true),
    body: JSON.stringify({ eventId }),
  });
  if (!res.ok) throw new EverydayError(describeStatus(res.status), res.status);
  return (await res.json()) as EverydayDay;
}

export async function requestScan(token: string, date: string): Promise<void> {
  const res = await fetch(`${BASE}/api/days/${date}/scan`, { method: "POST", headers: headers(token) });
  if (!res.ok) throw new EverydayError(describeStatus(res.status), res.status);
}

/** Authenticated media -> blob URL. Caller owns `URL.revokeObjectURL`. */
export async function fetchMediaUrl(token: string, rel: string, signal?: AbortSignal): Promise<string> {
  const res = await fetch(`${BASE}/${rel}`, { headers: headers(token), signal });
  if (!res.ok) throw new EverydayError(describeStatus(res.status), res.status);
  return URL.createObjectURL(await res.blob());
}

// ── pure helpers ─────────────────────────────────────────────────────────────

export function pickOf(day: EverydayDay): EverydayCandidate | null {
  return day.candidates.find((c) => c.eventId === day.pick) ?? null;
}

/** `front_door_reolink` -> `Front door reolink`. Display only; matching stays on the slug. */
export function cameraLabel(slug: string): string {
  const s = slug.replace(/_/g, " ").trim();
  return s ? s[0].toUpperCase() + s.slice(1) : slug;
}

/** `2026-10-26` -> `Mon, Oct 26` (parsed as a LOCAL date — `new Date("2026-10-26")` is UTC). */
export function dayLabel(date: string, locale?: string): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(locale, {
    weekday: "short",
    month: "short",
    day: "numeric",
  });
}

export function timeLabel(epochSec: number, locale?: string): string {
  return new Date(epochSec * 1000).toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit" });
}

/** Today in local time as YYYY-MM-DD. */
export function localDate(now = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

/** Streak of consecutive days with a pick, counting back from the newest summary. */
export function streak(days: EverydaySummary[]): number {
  const sorted = [...days].sort((a, b) => b.date.localeCompare(a.date));
  let n = 0;
  let expect: Date | null = null;
  for (const d of sorted) {
    const [y, m, dd] = d.date.split("-").map(Number);
    const cur = new Date(y, m - 1, dd);
    if (expect && cur.getTime() !== expect.getTime()) break;
    if (!d.pick) break;
    n++;
    expect = new Date(y, m - 1, dd - 1);
  }
  return n;
}

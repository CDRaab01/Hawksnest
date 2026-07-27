import type { CameraEvent } from "./cameraEvents";

/**
 * The `ring-timeline` service (sibling repo `hawksnest-automation`) — Ring's own recorded-footage
 * timeline, proxied same-origin by the Hawksnest nginx.
 *
 * It exists because Home Assistant can't provide this: ring-mqtt publishes a 40-slot event
 * `select` with **no event times** (which is why the timeline used to plot blocks at fabricated
 * 6-minute spacing) and produces no playable URL at all for the wired cameras. Ring's own API has
 * real times, durations, thumbnails and pre-signed mp4 URLs — this is that, normalized.
 */
const BASE = "/ring-timeline";

/** One Ring camera as the service sees it (`slug` is ring-mqtt's slugging of the device name). */
export interface RingDevice {
  id: number;
  name: string;
  slug: string;
}

/** A recording, as returned by the service (times are epoch ms). */
export interface RingRecording {
  id: string;
  startMs: number;
  endMs: number | null;
  durationSec: number | null;
  kind: string;
  person: boolean;
  url: string | null;
  /** When the pre-signed URL dies (~15 min out) — after this the timeline must be refetched. */
  urlExpiresAtMs: number | null;
  thumbnailUrl: string | null;
}

export interface RingTimeline {
  events: CameraEvent[];
  /** Playable URL per event id — kept beside the events so `CameraEvent` stays backend-agnostic. */
  urls: Map<string, string>;
  /** Earliest signed-URL expiry in the set; the player refetches before this. */
  expiresAtMs: number | null;
  /** Ring capped the result: there are older recordings in the window than these. */
  truncated: boolean;
}

/**
 * Normalize a Ring device name and an HA camera name the same way, so the two can be matched.
 * HA entity ids are NOT usable for this — they froze at first discovery and have drifted from the
 * Ring names since (HA's `camera.front_*` is Ring's "Front Driveway", `back2` is "Back Side Yard").
 * Friendly names come from ring-mqtt's discovery, so they track Ring.
 */
export function nameSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

/** The Ring device backing an HA camera, by name; falls back to the entity base for old setups. */
export function matchDevice(
  devices: RingDevice[],
  cameraName: string,
  entityBase: string,
): RingDevice | undefined {
  const byName = nameSlug(cameraName);
  return (
    devices.find((d) => d.slug === byName) ??
    devices.find((d) => d.slug === nameSlug(entityBase))
  );
}

/** Ring's `kind` → the timeline's label vocabulary (`cards.ts`-style: never throw on a new one). */
function labelOf(kind: string, person: boolean): string {
  if (kind === "ding" || kind === "doorbell") return "ding";
  if (person) return "person";
  return kind === "on_demand" || kind === "on_demand_link" ? "event" : "motion";
}

/** Shape a service recording into the timeline's `CameraEvent`. */
export function toCameraEvent(rec: RingRecording, cameraName: string): CameraEvent {
  return {
    id: rec.id,
    camera: cameraName,
    label: labelOf(rec.kind, rec.person),
    startMs: rec.startMs,
    // Unlike ring-mqtt's events, these carry a real duration — the timeline block is the real span
    // and no longer has to be learned from the loaded media.
    endMs: rec.endMs,
    hasClip: rec.url !== null,
    hasSnapshot: rec.thumbnailUrl !== null,
    thumbnailUrl: rec.thumbnailUrl,
    snapshotUrl: rec.thumbnailUrl,
  };
}

async function getJson(path: string, signal?: AbortSignal): Promise<unknown> {
  const res = await fetch(`${BASE}${path}`, { signal, headers: { accept: "application/json" } });
  if (!res.ok) throw new Error(`ring-timeline ${path}: HTTP ${res.status}`);
  return res.json();
}

/** The service's camera list. Rejects when the service is unreachable — callers degrade. */
export async function fetchRingDevices(signal?: AbortSignal): Promise<RingDevice[]> {
  const body = await getJson("/cameras", signal);
  if (!Array.isArray(body)) return [];
  return body.flatMap((d) => {
    const { id, name, slug } = (d ?? {}) as Partial<RingDevice>;
    return typeof id === "number" && typeof name === "string" && typeof slug === "string"
      ? [{ id, name, slug }]
      : [];
  });
}

/** One camera's recordings over `[fromMs, toMs]`, oldest-first, with their playable URLs. */
export async function fetchRingTimeline(
  deviceId: number,
  cameraName: string,
  fromMs: number,
  toMs: number,
  signal?: AbortSignal,
): Promise<RingTimeline> {
  const body = (await getJson(
    `/timeline?device_id=${deviceId}&from=${Math.round(fromMs)}&to=${Math.round(toMs)}`,
    signal,
  )) as { events?: RingRecording[]; truncated?: boolean };

  const recordings = Array.isArray(body.events) ? body.events : [];
  const urls = new Map<string, string>();
  const expiries: number[] = [];
  for (const rec of recordings) {
    if (rec.url) urls.set(rec.id, rec.url);
    if (rec.urlExpiresAtMs) expiries.push(rec.urlExpiresAtMs);
  }
  return {
    // Only playable recordings reach the timeline — the Ring-style invariant that every block on
    // the scrubber is watchable.
    events: recordings.filter((r) => r.url !== null).map((r) => toCameraEvent(r, cameraName)),
    urls,
    expiresAtMs: expiries.length ? Math.min(...expiries) : null,
    truncated: Boolean(body.truncated),
  };
}

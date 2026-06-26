import { createFixtureSource } from "./fixtureSource";
import { createHaSource } from "./haSource";
import { loadCredentials } from "./credentials";
import type { HistoryPoint, ServiceData, Source, WebRtcSignal } from "./source";
import type { AutomationConfig } from "../lib/automations";
import type { LogEvent } from "../lib/logbook";
import type { CameraEvent } from "../lib/cameraEvents";

let current: Source | null = null;

/**
 * Pick the data source: live HA when credentials are saved, otherwise the demo
 * fixture source. The store sees the same shape either way.
 */
function selectSource(): Source {
  const creds = loadCredentials();
  return creds ? createHaSource(creds) : createFixtureSource();
}

/** (Re)start the active source — call on app mount and after connect/disconnect. */
export function startConnection(): void {
  current?.stop();
  current = selectSource();
  void current.start();
}

/** Tear down the active source (app unmount). */
export function stopConnection(): void {
  current?.stop();
  current = null;
}

/**
 * Perform an HA service call through the active source (fixture simulates it;
 * live HA forwards it and reconciles via the state echo). Throws if the source
 * can't perform writes (e.g. not connected).
 */
export function callService(
  domain: string,
  service: string,
  data?: ServiceData,
): Promise<void> {
  if (!current?.callService) {
    return Promise.reject(new Error("Not connected."));
  }
  return current.callService(domain, service, data);
}

/**
 * Fetch recent state history for an entity through the active source (live HA
 * over the WebSocket; fixtures synthesize it). Rejects if the source can't
 * provide history (e.g. not connected).
 */
export function fetchHistory(
  entityId: string,
  hours: number,
): Promise<HistoryPoint[]> {
  if (!current?.fetchHistory) {
    return Promise.reject(new Error("History unavailable."));
  }
  return current.fetchHistory(entityId, hours);
}

/**
 * Fetch the home logbook through the active source (live HA over the WebSocket;
 * fixtures synthesize it). Rejects if the source can't provide a logbook.
 */
export function fetchLogbook(
  startMs: number,
  endMs: number,
  opts?: { entityIds?: string[] },
): Promise<LogEvent[]> {
  if (!current?.fetchLogbook) {
    return Promise.reject(new Error("History unavailable."));
  }
  return current.fetchLogbook(startMs, endMs, opts);
}

/**
 * Automation editor plumbing — read/write/delete HA automations through the
 * active source (live HA over the Config API; fixtures simulate in memory).
 * Reject if the source can't manage automations (e.g. not connected).
 */
export function getAutomationConfig(id: string): Promise<AutomationConfig | null> {
  if (!current?.getAutomationConfig) {
    return Promise.reject(new Error("Automations unavailable."));
  }
  return current.getAutomationConfig(id);
}

export function saveAutomationConfig(config: AutomationConfig): Promise<void> {
  if (!current?.saveAutomationConfig) {
    return Promise.reject(new Error("Automations unavailable."));
  }
  return current.saveAutomationConfig(config);
}

export function deleteAutomationConfig(id: string): Promise<void> {
  if (!current?.deleteAutomationConfig) {
    return Promise.reject(new Error("Automations unavailable."));
  }
  return current.deleteAutomationConfig(id);
}

/**
 * The on-demand live-stream URL for a camera (HLS from live HA; the bundled demo
 * clip in demo). Resolves null when the active source has no stream — the player
 * falls back to MJPEG/snapshot — so this never rejects on a missing capability.
 */
export function streamUrl(
  entityId: string,
  format: "hls" = "hls",
): Promise<string | null> {
  if (!current?.streamUrl) return Promise.resolve(null);
  return current.streamUrl(entityId, format);
}

/**
 * Recorded camera events over `[startMs, endMs]` for the timeline scrubber (live
 * HA reads Frigate; fixtures synthesize). Resolves [] when the active source
 * can't provide events, so the timeline renders empty rather than throwing.
 */
export function fetchCameraEvents(
  camera: string,
  startMs: number,
  endMs: number,
): Promise<CameraEvent[]> {
  if (!current?.fetchCameraEvents) return Promise.resolve([]);
  return current.fetchCameraEvents(camera, startMs, endMs);
}

/** Recorded-footage URL for `camera` over `[startMs, endMs]` (null if unsupported). */
export function recordingUrlAt(
  camera: string,
  startMs: number,
  endMs: number,
): string | null {
  return current?.recordingUrlAt?.(camera, startMs, endMs) ?? null;
}

/** Clip URL for one recorded event (null if unsupported). */
export function eventClipUrl(eventId: string): string | null {
  return current?.eventClipUrl?.(eventId) ?? null;
}

/** True when the active source can negotiate WebRTC (live HA, not demo). */
export function supportsWebRtc(): boolean {
  return typeof current?.webrtcOffer === "function";
}

/** Begin a WebRTC live session (see Source.webrtcOffer). Rejects if unsupported. */
export function webrtcOffer(
  entityId: string,
  offerSdp: string,
  onSignal: (signal: WebRtcSignal) => void,
): Promise<{ unsubscribe: () => void }> {
  if (!current?.webrtcOffer) {
    return Promise.reject(new Error("WebRTC unavailable."));
  }
  return current.webrtcOffer(entityId, offerSdp, onSignal);
}

/** Push a local trickle ICE candidate up for an in-flight WebRTC session. */
export function webrtcCandidate(
  sessionId: string,
  candidate: RTCIceCandidateInit,
): Promise<void> {
  if (!current?.webrtcCandidate) return Promise.resolve();
  return current.webrtcCandidate(sessionId, candidate);
}

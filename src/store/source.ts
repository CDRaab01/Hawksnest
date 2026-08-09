import type { AutomationConfig } from "../lib/automations";
import type { LogEvent } from "../lib/logbook";
import type { CameraEvent } from "../lib/cameraEvents";
import type { FootageSpan } from "../lib/ringFootage";

/** Optional service-call data. `entity_id` targets the entity; the rest is service data. */
export type ServiceData = { entity_id?: string } & Record<string, unknown>;

/**
 * One historical sample for an entity. `t` is epoch milliseconds; `state` is the
 * raw HA state string at that time (parse to a number for charting where it makes
 * sense — see Sparkline). Returned oldest-first.
 */
export interface HistoryPoint {
  t: number;
  state: string;
}

/**
 * A data Source feeds the entity store and (optionally) carries out control
 * actions. Phase 0/2 use the fixture source; the live HA source (WebSocket)
 * lands behind the same interface, so no screen or card changes when we swap.
 */
export interface Source {
  start: () => void | Promise<void>;
  stop: () => void;
  /**
   * Perform an HA service call (e.g. lock.lock, light.turn_on). The fixture
   * source simulates it locally; the live source forwards it to HA, whose state
   * echo reconciles the store. Throws if the source can't perform writes.
   */
  callService?: (
    domain: string,
    service: string,
    data?: ServiceData,
  ) => Promise<void>;
  /**
   * Fetch the recent state history for one entity over the last `hours`. The
   * live source asks HA over the WebSocket; the fixture source synthesizes a
   * plausible series. Rejects if the source can't provide history.
   */
  fetchHistory?: (entityId: string, hours: number) => Promise<HistoryPoint[]>;
  /**
   * Fetch the home logbook over `[startMs, endMs]` (optionally narrowed to
   * specific entities) for the History hub. The live source asks HA over the
   * WebSocket (`logbook/get_events`); the fixture source synthesizes events.
   * Rejects if the source can't provide a logbook.
   */
  fetchLogbook?: (
    startMs: number,
    endMs: number,
    opts?: { entityIds?: string[] },
  ) => Promise<LogEvent[]>;
  /**
   * Automation CRUD against HA's Config API (`/api/config/automation/config`).
   * The live source uses authenticated REST (same-origin with HA); the fixture
   * source simulates it in memory. Hawksnest only *edits* automations here — HA
   * itself runs them. Reject/throw if the source can't manage automations.
   */
  getAutomationConfig?: (id: string) => Promise<AutomationConfig | null>;
  saveAutomationConfig?: (config: AutomationConfig) => Promise<void>;
  deleteAutomationConfig?: (id: string) => Promise<void>;
  /**
   * The on-demand live-stream URL for a camera, in the requested container.
   * The live source asks HA over the WebSocket (`camera/stream`, format "hls")
   * for a low-latency feed; the fixture source returns the bundled demo clip so
   * demo mode plays real moving pixels. Resolves null when the source has no
   * stream for that entity (the player then falls back to MJPEG/snapshot).
   *
   * (WebRTC is the next tier above this — wired here once go2rtc is on the
   * cluster; the player's transport ladder already leaves a slot for it.)
   */
  streamUrl?: (entityId: string, format?: "hls") => Promise<string | null>;
  /**
   * Recorded motion/object events for a camera over `[startMs, endMs]`, powering
   * the timeline scrubber. The live source reads them from Frigate; the fixture
   * source synthesizes a believable 24h spread. `camera` is the Frigate camera
   * name. Returned oldest-first. Rejects if the source can't provide events.
   */
  fetchCameraEvents?: (
    camera: string,
    startMs: number,
    endMs: number,
  ) => Promise<CameraEvent[]>;
  /**
   * Where continuous recordings exist for a Frigate `camera` over `[startMs, endMs]`, as
   * coalesced drawable spans — the timeline's continuous lane. [] when unknown/unsupported:
   * the lane simply doesn't render. (The Ring lane arrives via the ring-timeline service
   * instead, bundled with the timeline fetch — this seam is the Frigate counterpart.)
   */
  fetchCameraFootage?: (
    camera: string,
    startMs: number,
    endMs: number,
  ) => Promise<FootageSpan[]>;
  /**
   * A playable URL for recorded footage of `camera` over `[startMs, endMs]`
   * (HLS VOD) — what the scrubber loads on seek. Pure URL builder, no fetch.
   * The fixture source returns the demo clip. Null when unsupported.
   */
  recordingUrlAt?: (camera: string, startMs: number, endMs: number) => string | null;
  /**
   * The same VOD URL, **signed** where the backend demands it.
   *
   * frigate-hass-integration rejects VOD *segment* requests without an `authSig` query parameter
   * (`VodSegmentProxyView` validates it unconditionally; a Bearer token does not satisfy it).
   * Playlists are exempt, so an unsigned URL fails in the worst way: the manifests load, every
   * `.m4s` 401s, and the video is black with no error raised.
   *
   * A signature authorises a path *prefix*, so one covers every segment in the page — but it is
   * tied to that page's `start/end`, so a new page needs a new signature. Signed URLs also need
   * no `Authorization` header, which is why this returns a URL rather than threading a token
   * into the media layer.
   */
  signedRecordingUrlAt?: (
    camera: string,
    startMs: number,
    endMs: number,
  ) => Promise<string | null>;
  /** A playable URL for one recorded event's clip. Null when unsupported. */
  eventClipUrl?: (eventId: string) => string | null;
  /**
   * A **downloadable** mp4 of `[startMs, endMs]` for a Frigate `camera` — clip export.
   *
   * Its own seam rather than a flag on `signedRecordingUrlAt`, because it differs in all three
   * things that matter: it addresses the integration's `RecordingProxyView` (`/recording/…`) not
   * the VOD view, the response is a single progressive mp4 that Frigate generates on demand with
   * **no `Content-Length`** (so no caller can show progress), and it is consumed by the *browser's
   * downloader* rather than by a media element — which is why it has to carry its own auth in the
   * query string.
   *
   * Unlike `signedRecordingUrlAt`, this resolves **null** rather than falling back to an unsigned
   * URL: the proxy 401s an unsigned export, and a 401 handed to a downloader is a silent failure.
   *
   * `[startMs, endMs]` must already be validated by `lib/clipExport` — this only builds and signs.
   */
  signedClipExportUrl?: (
    camera: string,
    startMs: number,
    endMs: number,
  ) => Promise<string | null>;
  /**
   * Begin a WebRTC live session for a camera. Sends the browser's SDP `offer`
   * to HA (`camera/webrtc/offer`, a subscribe-style command served by go2rtc)
   * and streams the negotiation back through `onSignal` (session id, answer,
   * trickle ICE candidates, or error). Resolves an unsubscribe handle. Only the
   * live HA source implements this; demo has no WebRTC (the player falls back).
   */
  webrtcOffer?: (
    entityId: string,
    offerSdp: string,
    onSignal: (signal: WebRtcSignal) => void,
  ) => Promise<{ unsubscribe: () => void }>;
  /**
   * Push a local trickle ICE candidate up to HA for an in-flight WebRTC session.
   * HA's `camera/webrtc/candidate` requires the `entityId` as well as the
   * `sessionId` — omitting it makes HA reject every candidate, so ICE never
   * completes and the live view silently falls back to the last snapshot.
   */
  webrtcCandidate?: (
    entityId: string,
    sessionId: string,
    candidate: RTCIceCandidateInit,
  ) => Promise<void>;
}

/** One message from HA's `camera/webrtc/offer` negotiation stream. */
export interface WebRtcSignal {
  type: "session" | "answer" | "candidate" | "error";
  session_id?: string;
  /** SDP answer (on `type: "answer"`). */
  answer?: string;
  /** Remote ICE candidate (on `type: "candidate"`). */
  candidate?: RTCIceCandidateInit | string;
  error?: string;
}

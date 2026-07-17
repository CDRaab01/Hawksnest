import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { LogicalCamera } from "../../lib/cameraModel";
import {
  fetchCameraEvents,
  recordingUrlAt,
  streamUrl,
  callService,
} from "../../store/connection";
import { useEntity } from "../../store/entityStore";
import type { CameraEvent } from "../../lib/cameraEvents";
import { vodPositionSeconds } from "../../lib/cameraEvents";
import { clipContaining, offsetInClipSeconds, clipSpanEndMs } from "../../lib/clipSeek";
import { ringEventsFromSelect } from "../../lib/ringEvents";
import { snapshotUrl } from "../../lib/cameraUrl";
import { LivePlayer } from "../LivePlayer";
import { HlsPlayer } from "../HlsPlayer";
import { CameraSwitcher } from "./CameraSwitcher";
import { SirenButton } from "./SirenButton";
import { TalkButton } from "./TalkButton";
import { Timeline24h } from "./Timeline24h";
import { TransportBar } from "./TransportBar";

const DAY_MS = 24 * 3600_000;

/** How long a clip switch is held off while the user is actively scrubbing, so
 *  dragging across several clips doesn't fire a select_option + stream per clip. */
const SCRUB_CLIP_DEBOUNCE_MS = 300;

function cameraNameOf(camera: LogicalCamera): string {
  return camera.id.split(".")[1] ?? camera.id;
}

/** Where the player is with the selected ring clip's stream URL. `failed` is a
 *  terminal, *visible* state (HA timed out / errored / the event rotated out of
 *  ring-mqtt's ~5-slot selector) — never left looking like it's still loading. */
type RingClipState =
  | { status: "idle" }
  | { status: "resolving"; clipId: string }
  | { status: "ready"; clipId: string; url: string }
  | { status: "failed"; clipId: string };

/**
 * Ring-style camera player: live feed + a scrubbable 24h timeline of recorded
 * events, an in-player camera switcher, and transport controls. The playhead is
 * `"live"` or an epoch-ms time. Dragging the timeline scrubs live: the playhead
 * follows the drag and, when it's inside a kept recording, the video seeks in
 * real time (forward and reverse); releasing keeps playing from that moment.
 *
 * The timeline shows **only playable recordings** (Ring-style: every block is
 * watchable). Recorded events come from one of two backends, transparently:
 * - **ring-mqtt** (`camera.eventSelectId` present): the last ~5 events from the
 *   event-selector entity; seeking inside one sets the selector
 *   (`select.select_option`) and plays the `camera.<base>_event` stream at the
 *   in-clip offset. A clip's real span is learned from the loaded media's
 *   duration (`endMs` arrives null).
 * - **Frigate / demo**: `fetchCameraEvents` (clip-bearing events only) + a
 *   continuous `recordingUrlAt` VOD (demo synthesizes both and plays the
 *   bundled clip).
 */
export function CameraPlayer({
  camera,
  cameras,
  onSelectCamera,
}: {
  camera: LogicalCamera;
  cameras: LogicalCamera[];
  onSelectCamera: (camera: LogicalCamera) => void;
}) {
  const cameraName = cameraNameOf(camera);
  const isRing = camera.eventSelectId !== null;
  const ringSelect = useEntity(camera.eventSelectId ?? "");

  const [window] = useState(() => {
    const end = Date.now();
    return { start: end - DAY_MS, end };
  });

  const [playhead, setPlayhead] = useState<number | "live">("live");
  const [paused, setPaused] = useState(false);
  // True while a timeline drag is in flight — clip switches debounce against it.
  const scrubbing = useRef(false);
  // The clip currently loaded in the player and its real media duration, once
  // known — refines timeline containment for open-ended (`endMs: null`) clips.
  const [loadedClip, setLoadedClip] = useState<{ id: string; durationMs: number } | null>(
    null,
  );
  // Where the selected ring clip's stream resolution is (see RingClipState).
  const [ringClip, setRingClip] = useState<RingClipState>({ status: "idle" });
  const [retryNonce, setRetryNonce] = useState(0);
  const ringClipRef = useRef(ringClip);
  ringClipRef.current = ringClip;

  // Reset playback state when the camera changes (the component is reused).
  // ringClip must reset too: ring-mqtt option ids ("Motion 1"…) repeat across
  // cameras, so a cached ready URL from the last camera would otherwise match.
  useEffect(() => {
    setPlayhead("live");
    setPaused(false);
    setLoadedClip(null);
    setRingClip({ status: "idle" });
    scrubbing.current = false;
  }, [camera.id]);

  // Demo/Frigate events come from the source; ring events come off the selector.
  const [fetched, setFetched] = useState<CameraEvent[]>([]);
  useEffect(() => {
    if (isRing) return;
    let active = true;
    fetchCameraEvents(cameraName, window.start, window.end)
      .then((e) => active && setFetched(e))
      .catch(() => active && setFetched([]));
    return () => {
      active = false;
    };
  }, [isRing, cameraName, window.start, window.end]);

  // Only real recordings make the timeline (Ring-style: every block is watchable —
  // no history-derived "maybe" markers).
  const events = useMemo(() => {
    if (!isRing) return fetched.filter((e) => e.hasClip);
    return ringEventsFromSelect(ringSelect, cameraName, window.end);
  }, [isRing, ringSelect, cameraName, window.end, fetched]);

  const isLive = playhead === "live";
  const headTime = isLive ? window.end : playhead;
  // The clip under the playhead (containment, not nearest): scrubbing can rest
  // anywhere, and gaps honestly show "no saved recording".
  const selected = useMemo(
    () =>
      isLive || !isRing
        ? undefined
        : (clipContaining(events, headTime, loadedClip?.id ?? null, loadedClip?.durationMs ?? null) ??
          undefined),
    [isLive, isRing, events, headTime, loadedClip],
  );

  const { prev, next } = useMemo(() => {
    const before = events.filter((e) => e.startMs < headTime);
    const after = events.filter((e) => e.startMs > headTime);
    return {
      prev: before.length ? before[before.length - 1] : null,
      next: after.length ? after[0] : null,
    };
  }, [events, headTime]);

  function seek(ms: number) {
    scrubbing.current = false;
    setPlayhead(Math.round(Math.min(window.end, Math.max(window.start, ms))));
    setPaused(false);
  }

  /** Live scrub: the playhead follows the drag; commit semantics stay on release (seek/onLive). */
  function scrub(ms: number) {
    scrubbing.current = true;
    setPlayhead(Math.round(Math.min(window.end, Math.max(window.start, ms))));
  }

  function goLive() {
    scrubbing.current = false;
    setPlayhead("live");
  }

  // ring recorded playback: select the event, then stream the `_event` camera.
  // Tri-state per clip — resolving / ready / failed — so a stream HA can't
  // produce (15s timeout, sleeping battery cam, rotated-out event) surfaces as
  // an honest error with a Retry, never a permanent "Loading…".
  useEffect(() => {
    if (!isRing || isLive || !selected || !camera.eventSelectId || !camera.eventStreamId) {
      return;
    }
    const clipId = selected.id;
    // Already resolving/ready for this clip (e.g. scrub within its span, or a
    // scrub that left and re-entered it) — don't re-fire select_option/stream.
    const cur = ringClipRef.current;
    if ((cur.status === "resolving" || cur.status === "ready") && cur.clipId === clipId) {
      return;
    }
    let active = true;
    let done = false;
    const run = async () => {
      setRingClip({ status: "resolving", clipId });
      setLoadedClip((lc) => (lc && lc.id === clipId ? lc : null));
      try {
        await callService("select", "select_option", {
          entity_id: camera.eventSelectId!,
          option: clipId,
        });
      } catch {
        /* selecting failed — still try to read whatever the event stream has */
      }
      let url: string | null = null;
      try {
        url = await streamUrl(camera.eventStreamId!);
      } catch {
        url = null;
      }
      if (!active) return;
      done = true;
      setRingClip(url ? { status: "ready", clipId, url } : { status: "failed", clipId });
    };
    const timer = setTimeout(() => void run(), scrubbing.current ? SCRUB_CLIP_DEBOUNCE_MS : 0);
    return () => {
      active = false;
      clearTimeout(timer);
      // A resolution cancelled mid-flight (scrubbed away / camera changed) must
      // not leave the state looking like it's still loading — reset so a return
      // to this clip re-resolves instead of skipping on a stale "resolving".
      if (!done) {
        setRingClip((s) =>
          s.status === "resolving" && s.clipId === clipId ? { status: "idle" } : s,
        );
      }
    };
    // selected is intentionally tracked by id only — its object identity changes
    // each render, but re-selecting the same event would re-trigger the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isRing, isLive, selected?.id, retryNonce, camera.eventSelectId, camera.eventStreamId]);

  // Continuous (Frigate) VOD spans the WHOLE window and is built once — scrubbing seeks within it
  // (see `seekSeconds`) rather than rebuilding a new playlist per seek, which used to tear down and
  // re-init the player on every scrub (the stutter, and the backwards-seek crash).
  const ringReady =
    selected && ringClip.status === "ready" && ringClip.clipId === selected.id
      ? ringClip
      : null;
  const recordingSrc = isLive
    ? null
    : isRing
      ? (ringReady?.url ?? null)
      : recordingUrlAt(cameraName, window.start, window.end);
  // In-media seek position: the continuous VOD seeks from the window start; a
  // ring clip seeks from ITS start (so scrubbing inside a clip previews live).
  const seekSeconds = isLive
    ? undefined
    : isRing
      ? ringReady && selected
        ? offsetInClipSeconds(selected, headTime)
        : undefined
      : vodPositionSeconds(headTime, window.start);

  // Learn the loaded ring clip's real duration from the media, refining the
  // timeline block width + containment span for its open-ended event.
  const onDuration = useCallback((seconds: number) => {
    const cur = ringClipRef.current;
    if (cur.status !== "ready") return;
    const durationMs = Math.round(seconds * 1000);
    setLoadedClip((lc) =>
      lc && lc.id === cur.clipId && lc.durationMs === durationMs
        ? lc
        : { id: cur.clipId, durationMs },
    );
  }, []);
  // An HLS error after the URL resolved is a failure too (dead playlist, expired token).
  const onPlaybackError = useCallback(() => {
    setRingClip((s) => (s.status === "ready" ? { status: "failed", clipId: s.clipId } : s));
  }, []);

  // Give the timeline the loaded clip's real span so chip width agrees with containment.
  const displayEvents = useMemo(
    () =>
      loadedClip
        ? events.map((e) =>
            e.id === loadedClip.id && e.endMs === null
              ? { ...e, endMs: clipSpanEndMs(e, loadedClip.id, loadedClip.durationMs) }
              : e,
          )
        : events,
    [events, loadedClip],
  );

  const placeholderState: "resolving" | "failed" | "none" =
    isRing && selected
      ? ringClip.status === "failed" && ringClip.clipId === selected.id
        ? "failed"
        : "resolving"
      : "none";

  return (
    <div className="space-y-md">
      <div className="flex items-center justify-between gap-md">
        <CameraSwitcher cameras={cameras} current={camera} onSelect={onSelectCamera} />
        <div className="flex items-center gap-sm">
          {isRing && isLive && <TalkButton src={cameraName} />}
          {camera.sirenSwitchId && <SirenButton entityId={camera.sirenSwitchId} />}
          <span
            className={[
              "flex items-center gap-xs rounded-sm px-sm py-xs caption-label",
              isLive ? "text-recovery" : "text-ink-dim",
            ].join(" ")}
          >
            <span
              className={[
                "h-2 w-2 rounded-full",
                isLive ? "bg-recovery" : "bg-ink-faint",
              ].join(" ")}
            />
            {isLive ? "Live" : "Recorded"}
          </span>
        </div>
      </div>

      {isLive ? (
        <LivePlayer entity={camera.liveEntity} />
      ) : recordingSrc ? (
        <HlsPlayer
          src={recordingSrc}
          paused={paused}
          loop={!isRing}
          seekSeconds={seekSeconds}
          onDuration={isRing ? onDuration : undefined}
          onError={isRing ? onPlaybackError : undefined}
        />
      ) : (
        // Scrubbed to a past moment with no footage on screen: a clip is
        // resolving, resolution failed (Retry), or there's simply no recording
        // kept for this time — say so over the snapshot rather than snapping
        // the frame back to the live feed.
        <ScrubbedPlaceholder
          snapshot={snapshotUrl(camera.snapshotEntity)}
          state={placeholderState}
          onRetry={() => setRetryNonce((n) => n + 1)}
        />
      )}

      <Timeline24h
        events={displayEvents}
        startMs={window.start}
        endMs={window.end}
        playhead={playhead}
        onSeek={seek}
        onScrub={scrub}
        onLive={goLive}
      />

      <TransportBar
        isLive={isLive}
        isPaused={paused}
        canPrev={prev !== null}
        canNext={next !== null || !isLive}
        onPrev={() => prev && seek(prev.startMs)}
        onNext={() => (next ? seek(next.startMs) : goLive())}
        onTogglePlay={() => setPaused((p) => !p)}
        onLive={goLive}
      />
    </div>
  );
}

/**
 * The frame shown when the timeline is scrubbed to a moment with no footage on
 * screen — the camera's snapshot, dimmed, with an honest note. `resolving` means
 * a playable clip's stream is still being produced; `failed` means HA couldn't
 * produce it (timeout / error / the event rotated out of ring-mqtt's selector)
 * and offers a Retry; `none` means no recording is kept for this time.
 */
function ScrubbedPlaceholder({
  snapshot,
  state,
  onRetry,
}: {
  snapshot: string | null;
  state: "resolving" | "failed" | "none";
  onRetry?: () => void;
}) {
  return (
    <div className="relative aspect-video w-full overflow-hidden rounded-lg bg-panel">
      {snapshot && (
        <img
          src={snapshot}
          alt=""
          className="absolute inset-0 h-full w-full object-cover"
        />
      )}
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-sm bg-black/45">
        <span
          className={["font-body text-body", state === "failed" ? "text-streak" : "text-ink"].join(
            " ",
          )}
        >
          {state === "resolving"
            ? "Loading recording…"
            : state === "failed"
              ? "Couldn't load this recording"
              : "No saved recording for this moment"}
        </span>
        {state === "failed" && onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-full bg-panel-high px-lg py-sm font-body text-body text-ink transition-colors duration-fast hover:bg-panel"
          >
            Retry
          </button>
        )}
      </div>
    </div>
  );
}

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { LogicalCamera } from "../../lib/cameraModel";
import { fetchCameraEvents, recordingUrlAt } from "../../store/connection";
import { resolveRingClipUrl } from "../../store/ringClip";
import {
  fetchRingDevices,
  fetchRingFootage,
  fetchRingTimeline,
  matchDevice,
  type RingTimeline,
} from "../../lib/ringTimeline";
import {
  chooseRecordedSource,
  footageSpans,
  type RingFootage,
} from "../../lib/ringFootage";
import { frigateHasCamera, primeFrigateCameras } from "../../lib/frigate";
import { hasRealRecordings, recordedBackendOf } from "../../lib/recordedBackend";
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
 *   (`select.select_option`) and plays the recording ring-mqtt publishes back on
 *   that selector (`recordingUrl`), at the in-clip offset. A clip's real span is
 *   learned from the loaded media's duration (`endMs` arrives null).
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

  // Frigate membership is only known after a config fetch, so prime it once and
  // re-derive on the result. Fails closed (see `frigate.ts`): until it lands, and
  // forever if there's no Frigate, every camera reads exactly as it did before.
  const [frigateNonce, setFrigateNonce] = useState(0);
  useEffect(() => {
    let active = true;
    void primeFrigateCameras().then(() => active && setFrigateNonce((n) => n + 1));
    return () => {
      active = false;
    };
  }, []);

  const backend = useMemo(
    () =>
      recordedBackendOf({
        hasRingSelector: camera.eventSelectId !== null,
        hasFrigateCamera: frigateHasCamera(cameraName),
      }),
    // frigateNonce is the re-derive trigger: `frigateHasCamera` reads a module cache.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [camera.eventSelectId, cameraName, frigateNonce],
  );
  // Ring keeps its own name because the paths below are Ring-specific mechanics
  // (selector resolution, ring-timeline signatures), not "has recordings".
  const isRing = backend === "ring";
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

  // Ring's OWN timeline, via the ring-timeline service — real event times, real spans, and
  // directly playable URLs. Preferred over the selector for ring cameras because the selector
  // has no event times at all (its blocks were plotted on fabricated 6-minute spacing) and
  // yields nothing playable on the wired cameras. Null = service unreachable or this camera
  // isn't one of its devices; the selector path below still covers that.
  const [timeline, setTimeline] = useState<RingTimeline | null>(null);
  // The 24/7 continuous track for the same window. Null = no track (battery cams, doorbell) or the
  // service couldn't answer; either way there's simply no continuous lane and no continuous source.
  const [footage, setFootage] = useState<RingFootage | null>(null);
  const [timelineNonce, setTimelineNonce] = useState(0);
  useEffect(() => {
    if (!isRing) return;
    let active = true;
    const abort = new AbortController();
    void (async () => {
      try {
        const devices = await fetchRingDevices(abort.signal);
        const device = matchDevice(devices, camera.name, cameraName);
        if (!device) throw new Error(`no ring-timeline device for ${camera.name}`);
        // Both halves of the recorded past, from one device lookup and on one refresh cycle —
        // they expire on the same ~15-minute signature clock. `allSettled`, not `all`: a camera
        // with no 24/7 track (or a /footage that errors) must not cost us the event timeline.
        const [t, f] = await Promise.allSettled([
          fetchRingTimeline(device.id, cameraName, window.start, window.end, abort.signal),
          fetchRingFootage(device.id, window.start, window.end, abort.signal),
        ]);
        if (!active) return;
        if (t.status === "rejected") throw t.reason;
        setTimeline(t.value);
        setFootage(f.status === "fulfilled" && f.value.continuous ? f.value : null);
      } catch {
        // Degrade to the ring-mqtt selector rather than showing an empty timeline.
        if (active) {
          setTimeline(null);
          setFootage(null);
        }
      }
    })();
    return () => {
      active = false;
      abort.abort();
    };
  }, [isRing, camera.id, camera.name, cameraName, window.start, window.end, timelineNonce]);

  // Ring signs its media URLs for ~15 minutes, so a timeline left open goes unplayable.
  // Refetch a minute before the earliest expiry — cheap (the service caches) and keeps every
  // block on screen watchable for as long as the player is open. The continuous track's URLs are
  // signed the same way, so whichever dies first drives the refresh for both.
  const expiresAtMs = useMemo(() => {
    const candidates = [timeline?.expiresAtMs, footage?.expiresAtMs].filter(
      (v): v is number => typeof v === "number",
    );
    return candidates.length ? Math.min(...candidates) : null;
  }, [timeline?.expiresAtMs, footage?.expiresAtMs]);
  useEffect(() => {
    if (!expiresAtMs) return;
    const delay = Math.max(5_000, expiresAtMs - Date.now() - 60_000);
    const timer = setTimeout(() => setTimelineNonce((n) => n + 1), delay);
    return () => clearTimeout(timer);
  }, [expiresAtMs]);

  // Only real recordings make the timeline (Ring-style: every block is watchable —
  // no history-derived "maybe" markers).
  const events = useMemo(() => {
    if (!isRing) return fetched.filter((e) => e.hasClip);
    if (timeline) return timeline.events;
    return ringEventsFromSelect(ringSelect, cameraName, window.end);
  }, [isRing, timeline, ringSelect, cameraName, window.end, fetched]);

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

  // ring recorded playback: select the event, then take the recording URL
  // ring-mqtt publishes for it (see resolveRingClipUrl). Tri-state per clip —
  // resolving / ready / failed — so a recording Ring can't produce (timeout,
  // rotated-out event, no Protect subscription) surfaces as an honest error with
  // a Retry, never a permanent "Loading…".
  // Skipped entirely when the ring-timeline service answered: its events already carry a
  // playable URL, so there is nothing to select, wait for, or fail at.
  useEffect(() => {
    if (!isRing || isLive || !selected || !camera.eventSelectId || timeline) return;
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
      let url: string | null = null;
      try {
        url = await resolveRingClipUrl({
          selectId: camera.eventSelectId!,
          option: clipId,
          eventStreamId: camera.eventStreamId,
        });
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
  }, [isRing, isLive, selected?.id, retryNonce, camera.eventSelectId, camera.eventStreamId, timeline]);

  // Continuous (Frigate) VOD spans the WHOLE window and is built once — scrubbing seeks within it
  // (see `seekSeconds`) rather than rebuilding a new playlist per seek, which used to tear down and
  // re-init the player on every scrub (the stutter, and the backwards-seek crash).
  const ringReady =
    selected && ringClip.status === "ready" && ringClip.clipId === selected.id
      ? ringClip
      : null;
  // On the ring-timeline service path, ONE pure function decides which recorded source plays: the
  // 24/7 continuous track when it covers this moment, else the event clip (both arrive with their
  // URLs already signed, so neither has a round trip or a loading state). The ring-mqtt selector
  // path has neither a urls map nor footage, so it keeps its own per-clip resolution above.
  const recorded = useMemo(
    () =>
      isLive || !isRing || !timeline
        ? null
        : chooseRecordedSource({
            headMs: headTime,
            segments: footage?.segments ?? [],
            events,
            urls: timeline.urls,
            loadedClipId: loadedClip?.id ?? null,
            loadedDurationMs: loadedClip?.durationMs ?? null,
          }),
    [isLive, isRing, timeline, footage, events, headTime, loadedClip],
  );
  const playable = recorded?.kind === "footage" || recorded?.kind === "clip" ? recorded : null;

  // The continuous VOD URL for a non-Ring backend. `recordingUrlAt` ALWAYS returns
  // a URL — it just formats the window into a path, it doesn't know whether Frigate
  // kept anything for that span — so a scrub into a gap would otherwise mount a
  // playlist that 404s and sit there dead. `vodFailed` records the src that failed
  // so the placeholder can take over; it's keyed by src, not a flag, so changing
  // camera or window re-arms it automatically.
  const [vodFailed, setVodFailed] = useState<string | null>(null);
  const vodSrc = isLive || isRing ? null : recordingUrlAt(cameraName, window.start, window.end);

  const recordingSrc = isLive
    ? null
    : isRing
      ? (playable?.url ?? ringReady?.url ?? null)
      : vodSrc === vodFailed
        ? null
        : vodSrc;
  // In-media seek position: the continuous VOD seeks from the window start; a
  // ring clip (or a stitched footage segment) seeks from ITS start, so scrubbing
  // inside it previews live.
  const seekSeconds = isLive
    ? undefined
    : isRing
      ? (playable?.seekSeconds ??
        (ringReady && selected ? offsetInClipSeconds(selected, headTime) : undefined))
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
  // On the ring-timeline path the overwhelmingly likely cause is Ring's ~15-minute signature
  // having expired, so refetch the timeline (fresh URLs) once per clip before calling it a
  // failure — the once-per-clip guard is what stops a genuinely dead clip from looping.
  // Keyed by the *source* being played, not the event: on the continuous track the user can sit on
  // one stitched segment for far longer than a signature lives, and that segment has no event id.
  const sourceKey =
    recorded?.kind === "footage" ? `footage:${recorded.segment.startMs}` : (selected?.id ?? null);
  const refetchedFor = useRef<string | null>(null);
  const onPlaybackError = useCallback(() => {
    // Non-Ring (Frigate) has no signed URL to refresh and no per-clip state to
    // fail — the whole-window VOD either plays or it doesn't. Record which src
    // died so the placeholder replaces the dead player, with a Retry.
    if (!isRing) {
      if (vodSrc) setVodFailed(vodSrc);
      return;
    }
    if (timeline && sourceKey && refetchedFor.current !== sourceKey) {
      refetchedFor.current = sourceKey;
      setTimelineNonce((n) => n + 1);
      return;
    }
    setRingClip((s) => (s.status === "ready" ? { status: "failed", clipId: s.clipId } : s));
  }, [isRing, vodSrc, timeline, sourceKey]);

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

  // On the ring-timeline path there is no resolving step: an event on the timeline arrived with
  // its URL, so if we somehow have no source for it that's a failure, never a spinner. A moment
  // covered only by an end-to-end-encrypted segment is its own case — footage WAS recorded, this
  // player just has no key for it, which is neither a failure to retry nor "nothing recorded".
  const placeholderState: "resolving" | "failed" | "encrypted" | "none" = !isRing
    ? // Frigate: a whole-window VOD that won't play is a failure worth retrying,
      // not "nothing was recorded" — the window is 24h and Frigate is recording
      // continuously, so a total miss is far more likely a transient fetch than a
      // genuinely empty day. Demo/no-NVR never gets here (its src always plays).
      backend === "frigate" && vodFailed
      ? "failed"
      : "none"
    : timeline
      ? recorded?.kind === "encrypted"
        ? "encrypted"
        : selected
          ? "failed"
          : "none"
      : selected
        ? ringClip.status === "failed" && ringClip.clipId === selected.id
          ? "failed"
          : "resolving"
        : "none";

  // The continuous track as drawable spans (coalesced so a stitch seam doesn't read as a gap).
  const footageLane = useMemo(() => (footage ? footageSpans(footage.segments) : []), [footage]);

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
        <LivePlayer
          entity={camera.liveEntity}
          // Unconditional: go2rtc serves whatever streams it's configured for, and
          // that is no longer "Ring cameras only" — the Reolink main stream is one.
          // `go2rtcMaybeAvailable` already declines unknown streams off the fetched
          // stream list, so the gate belongs there, not in a per-backend guess here.
          go2rtcSrc={cameraName}
        />
      ) : recordingSrc ? (
        <HlsPlayer
          src={recordingSrc}
          paused={paused}
          // Only the demo/no-NVR source loops — it hands back the same bundled clip
          // for every seek. A real recording is finite and must end where it ends.
          loop={!hasRealRecordings(backend)}
          seekSeconds={seekSeconds}
          // Duration-learning is a Ring mechanic: it refines an open-ended
          // (`endMs: null`) selector event's span. Frigate events carry real ends.
          onDuration={isRing ? onDuration : undefined}
          onError={hasRealRecordings(backend) ? onPlaybackError : undefined}
        />
      ) : (
        // Scrubbed to a past moment with no footage on screen: a clip is
        // resolving, resolution failed (Retry), or there's simply no recording
        // kept for this time — say so over the snapshot rather than snapping
        // the frame back to the live feed.
        <ScrubbedPlaceholder
          snapshot={snapshotUrl(camera.snapshotEntity)}
          state={placeholderState}
          onRetry={() => {
            // On the service path, retrying means fetching fresh signed URLs; on the
            // selector path it means re-running the per-clip resolution; on Frigate
            // it means re-arming the same VOD src so the player remounts it.
            refetchedFor.current = null;
            if (!isRing) setVodFailed(null);
            else if (timeline) setTimelineNonce((n) => n + 1);
            else setRetryNonce((n) => n + 1);
          }}
        />
      )}

      <Timeline24h
        events={displayEvents}
        footage={footageLane}
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
  state: "resolving" | "failed" | "encrypted" | "none";
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
              : state === "encrypted"
                ? "This footage is end-to-end encrypted"
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

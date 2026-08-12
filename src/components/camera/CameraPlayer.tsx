import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { LogicalCamera } from "../../lib/cameraModel";
import {
  fetchCameraEvents,
  fetchCameraFootage,
  signedClipExportUrl,
  signedRecordingUrlAt,
} from "../../store/connection";
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
  type FootageSpan,
  type RingFootage,
} from "../../lib/ringFootage";
import {
  FRIGATE_RETENTION_SENSOR,
  frigateRetentionDays,
  isFrigateCamera,
  frigateCameraName,
} from "../../lib/frigate";
import { retentionRange, vodPageFor, vodPositionSecondsInPage } from "../../lib/vodWindow";
import {
  clipFileName,
  defaultSelection,
  exportBounds,
  nudge as nudgeClip,
  setEdge as setClipEdge,
  type ClipSelection,
} from "../../lib/clipExport";
import { ClipExportBar, type ClipExportState } from "./ClipExportBar";
import { hasRealRecordings, recordedBackendOf } from "../../lib/recordedBackend";
import { useEntity } from "../../store/entityStore";
import type { CameraEvent } from "../../lib/cameraEvents";
import { clipContaining, offsetInClipSeconds, clipSpanEndMs } from "../../lib/clipSeek";
import { ringEventsFromSelect } from "../../lib/ringEvents";
import { snapshotUrl } from "../../lib/cameraUrl";
import { loadCredentials } from "../../store/credentials";
import { go2rtcMaybeAvailable, go2rtcStreamsKnown, primeGo2rtcStreams } from "../../lib/go2rtc";
import { LivePlayer } from "../LivePlayer";
import { HlsPlayer } from "../HlsPlayer";
import { CameraSwitcher } from "./CameraSwitcher";
import { SirenButton } from "./SirenButton";
import { TalkButton } from "./TalkButton";
import { MuteButton } from "./MuteButton";
import { SnapshotButton } from "./SnapshotButton";
import { QualityToggle } from "./QualityToggle";
import { EventDescription } from "./EventDescription";
import { PtzPanel } from "./PtzPanel";
import { resolvePtz } from "../../lib/cameraPtz";
import { useEntityStore } from "../../store/entityStore";
import { Move, Scissors } from "lucide-react";
import { Timeline24h } from "./Timeline24h";
import { TransportBar } from "./TransportBar";
import { ZoomableFrame } from "./ZoomableFrame";

const DAY_MS = 24 * 3600_000;

/** How long a clip switch is held off while the user is actively scrubbing, so
 *  dragging across several clips doesn't fire a select_option + stream per clip. */
const SCRUB_CLIP_DEBOUNCE_MS = 300;

function cameraNameOf(camera: LogicalCamera): string {
  return camera.id.split(".")[1] ?? camera.id;
}

/**
 * Whether `url` is same-origin with the page — which decides how a clip export is saved.
 *
 * The `download` attribute is honoured only same-origin; cross-origin the browser ignores it and,
 * with no `Content-Disposition` from Frigate, renders the mp4 instead of saving it.
 */
function isSameOrigin(url: string): boolean {
  try {
    return new URL(url, globalThis.location.href).origin === globalThis.location.origin;
  } catch {
    return false;
  }
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

  // Frigate membership is read straight off the camera's HA entity — the integration stamps
  // `client_id`/`camera_name` onto it. No fetch, no cache, no priming effect: the config route
  // this used to poll does not exist (see `frigate.ts`), which is why it silently reported "no
  // Frigate" for every camera.

  const backend = useMemo(
    () =>
      recordedBackendOf({
        hasRingSelector: camera.eventSelectId !== null,
        hasFrigateCamera: isFrigateCamera(camera.liveEntity),
      }),
    [camera.eventSelectId, camera.liveEntity],
  );
  // Ring keeps its own name because the paths below are Ring-specific mechanics
  // (selector resolution, ring-timeline signatures), not "has recordings".
  const isRing = backend === "ring";
  const isFrigate = backend === "frigate";
  // Frigate's own name for this camera, which is authoritative over the HA slug for URL building
  // (see `frigate.ts`). They are normally identical; when they are not, the slug 404s.
  const frigateExportName = frigateCameraName(camera.liveEntity) ?? cameraName;
  const ringSelect = useEntity(camera.eventSelectId ?? "");

  // Pin "now" once so the timeline doesn't slide under the user mid-session.
  const [nowAnchor] = useState(() => Date.now());

  // Camera audio starts muted (autoplay policy) — MuteButton is the way back.
  // Applies to live AND recorded playback; Frigate records the audio track too.
  const [muted, setMuted] = useState(true);

  // Live quality: High = the go2rtc main stream, Low = the camera's sub stream
  // (`<name>_sub` in go2rtc). The toggle renders only when go2rtc actually lists
  // the sub stream — same stream-list gate the go2rtc live tier itself uses, so
  // Ring cameras (no `_sub`) and demo cameras never see it.
  const [quality, setQuality] = useState<"high" | "low">("high");
  const [go2rtcKnown, setGo2rtcKnown] = useState(() => go2rtcStreamsKnown());
  useEffect(() => {
    let active = true;
    void primeGo2rtcStreams().then(() => active && setGo2rtcKnown(true));
    return () => {
      active = false;
    };
  }, []);
  // Movement controls, if this camera has any. Resolved from the entity ids that
  // exist rather than derived from the camera name — see `cameraPtz.ts` for why
  // (the stairway's Reolink device is named differently from its Frigate camera).
  const entityIds = useEntityStore((s) => s.entities);
  const ptz = useMemo(
    () => resolvePtz(cameraName, Object.keys(entityIds)),
    [cameraName, entityIds],
  );
  const [showPtz, setShowPtz] = useState(false);

  const subSrc = `${cameraName}_sub`;
  const hasSubStream = go2rtcKnown && go2rtcMaybeAvailable(subSrc);
  // Whether anything can be spoken through this camera — the Talk gate, and the
  // web twin of Android's `canReachSpeaker`. go2rtc is what carries the audio
  // backchannel, so a camera it doesn't serve has no path to a speaker at all.
  //
  // This was `isRing` until 2026-08-05, which is a fact about where a camera's
  // RECORDINGS live and says nothing about its speaker — the same category error
  // the live tier made, and it excluded all seven Reolinks from a feature they
  // support.
  //
  // `go2rtcKnown &&` is not redundant: `go2rtcMaybeAvailable` is deliberately
  // OPTIMISTIC while the stream list is in flight, which is right for choosing a
  // transport that can step down and wrong for a button. Fails closed here.
  const canReachSpeaker = go2rtcKnown && go2rtcMaybeAvailable(cameraName);
  // A camera without a sub stream always plays High — don't let a stale Low
  // selection from the previous camera silently pick a nonexistent stream.
  const liveGo2rtcSrc = quality === "low" && hasSubStream ? subSrc : cameraName;
  // Frigate VOD's nested manifest needs a Bearer token that a URL signature cannot cover.
  const [mediaAuthToken] = useState(() => loadCredentials()?.token ?? null);
  // How far back the timeline reaches. Ring stays at 24h — its recorded path is the last handful
  // of cloud events, so a longer span would render a mostly-empty timeline and pull days of Ring
  // history for nothing. Frigate keeps continuous video, so its timeline spans the ACTUAL
  // retention Frigate reports (`record.continuous.days`, surfaced by an HA REST sensor —
  // see lib/frigate.ts) with the old hand-synced constant as fallback.
  const retentionEntity = useEntity(FRIGATE_RETENTION_SENSOR);
  const window = useMemo(() => {
    if (backend === "frigate") {
      const r = retentionRange(nowAnchor, frigateRetentionDays(retentionEntity));
      return { start: r.startMs, end: r.endMs };
    }
    return { start: nowAnchor - DAY_MS, end: nowAnchor };
  }, [backend, nowAnchor, retentionEntity]);

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
  // (Clip-export state resets alongside this, in its own effect down in the clip section.)
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

  // The Frigate continuous lane — where recordings actually exist, so the strip shows "you can
  // scrub anywhere here" instead of rendering blank between event chips. Ring's lane arrives
  // bundled with its timeline fetch below; this is the Frigate counterpart (already coalesced
  // by the source). [] until it resolves — the lane just appears, nothing blocks on it.
  const [frigateFootage, setFrigateFootage] = useState<FootageSpan[]>([]);
  useEffect(() => {
    setFrigateFootage([]);
    if (isRing || !isFrigate) return;
    let active = true;
    fetchCameraFootage(cameraName, window.start, window.end)
      .then((spans) => active && setFrigateFootage(spans))
      .catch(() => active && setFrigateFootage([]));
    return () => {
      active = false;
    };
  }, [isRing, isFrigate, cameraName, window.start, window.end]);

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

  // The event whose AI description the strip shows. Separate from `selected`
  // above, which is deliberately Ring-only (it drives per-clip stream
  // resolution); descriptions are a Frigate feature, so this one is
  // backend-agnostic and needs no loaded-clip refinement — Frigate events carry
  // real end times.
  const describedEvent = useMemo(
    () => clipContaining(events, headTime, null, null) ?? null,
    [events, headTime],
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
  // The VOD is a bounded PAGE around the playhead, not the whole window. A Frigate manifest is
  // capped at ~1024 segments by nginx-vod-module (~3h at these segment lengths) and 503s past it,
  // so a window spanning days — or even the old 24h — cannot be one manifest. Pages are
  // grid-aligned, so scrubbing within a page keeps the same URL and the player does not reload.
  const vodPage = useMemo(
    () =>
      isLive || isRing
        ? null
        : vodPageFor(headTime, { startMs: window.start, endMs: window.end }),
    [isLive, isRing, headTime, window.start, window.end],
  );
  // Frigate VOD must be SIGNED or every segment 401s and the video is silently black — see
  // `Source.signedRecordingUrlAt`. Signing is a websocket round trip, so it resolves in an effect
  // rather than during render. Keyed on the page, so it re-signs exactly when the page turns.
  const [vodSrc, setVodSrc] = useState<string | null>(null);
  // Depend on the page's NUMBERS, never on the page object.
  //
  // `vodPage` is rebuilt by its useMemo whenever `headTime` moves — i.e. on every pointer move of
  // a scrub — and React compares deps with `Object.is`, so including the object made this effect
  // re-run per frame: `setVodSrc(null)` blanked the player to the placeholder and a fresh
  // `auth/sign_path` round trip went out, every frame. That is exactly the reload `vodWindow.ts`
  // grid-aligns pages to avoid ("scrubbing within a page keeps the same URL and the player does
  // not reload"). Android never had it only because Compose keys `produceState` by `equals` and
  // `TimeRange` is a data class.
  const pageStartMs = vodPage?.startMs ?? null;
  const pageEndMs = vodPage?.endMs ?? null;
  useEffect(() => {
    // Reset first, not just on the null branch: state would otherwise hold the PREVIOUS page's
    // URL while the new signature resolves, and the player would briefly play the old page with a
    // seek computed against the new page's origin — a plausible-but-wrong moment. (The Android
    // player had the worse version of this: its page-turn swap leaked the old ExoPlayer entirely.)
    setVodSrc(null);
    if (pageStartMs === null || pageEndMs === null) return;
    let active = true;
    void signedRecordingUrlAt(cameraName, pageStartMs, pageEndMs)
      .then((url) => active && setVodSrc(url))
      .catch(() => active && setVodSrc(null));
    return () => {
      active = false;
    };
  }, [cameraName, pageStartMs, pageEndMs]);

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
      : // Offset within the PAGE, not the window — the VOD's zero is the page start. Using
        // window.start here would seek to a plausible but wrong moment, which is worse than an
        // obvious failure because it looks like the recording is simply wrong.
        vodPositionSecondsInPage(headTime, vodPage?.startMs ?? window.start);

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
  // Ring's spans come off its timeline fetch; Frigate's arrive pre-coalesced from the source.
  const ringLane = useMemo(() => (footage ? footageSpans(footage.segments) : []), [footage]);
  const footageLane = isRing ? ringLane : frigateFootage;

  // --- Clip export -----------------------------------------------------------------------
  // Note what is NOT here: none of this state feeds `vodPage`, `vodSrc` or `recordingSrc`.
  // Entering clip mode must be invisible to the playback stack — a selection that re-keyed the
  // player would re-prepare (and re-sign) the VOD on every nudge. See ARCHITECTURE.md's
  // player-identity trap.
  const [clipSel, setClipSel] = useState<ClipSelection | null>(null);
  const [clipState, setClipState] = useState<ClipExportState>("idle");
  const [clipError, setClipError] = useState<string | null>(null);

  // A selection belongs to ONE camera's timeline, so it cannot survive a camera change. Left
  // alive it did real damage rather than merely looking odd: the export bar replaces the
  // transport bar, so switching cameras mid-selection removed prev/next/play, and Download then
  // asked Frigate to cut that range out of the NEW camera — which Frigate may not record at all.
  // Its own effect rather than a line in the reset above, so the clip state stays declared and
  // managed in one place (see the note at the top of this section).
  useEffect(() => {
    setClipSel(null);
    setClipState("idle");
    setClipError(null);
  }, [camera.id]);

  /**
   * Whether clip mode may be ON SCREEN right now — the single gate every clip surface reads.
   *
   * It repeats the Clip *button*'s own condition on purpose. The button being hidden is not the
   * same as the mode being off: going Live (or landing on a Ring camera) hides the button while
   * `clipSel` is still set, and the export bar used to render anyway — replacing the transport
   * bar with a range editor for a range that cannot be exported, and leaving no play/prev/next.
   * Deriving the gate once means the button, the timeline band and the bar cannot disagree.
   */
  const clipMode = isFrigate && !isLive;

  // Live `Date.now()`, deliberately NOT the pinned `nowAnchor` the timeline lays itself out with:
  // a player left open for hours would otherwise keep offering a start time Frigate has since
  // rotated out of retention. Recomputed per render; it feeds no effect, so it cannot churn one.
  const clipBounds = exportBounds({ startMs: window.start, endMs: window.end }, Date.now());

  /**
   * Apply an edit to the selection.
   *
   * Takes an updater rather than a value so *relative* edits compose. The nudge buttons are
   * `cur + delta`, and two taps landing in one render batch would otherwise both read the same
   * stale selection and the second would be silently lost — which is exactly what a user doing
   * "+1s +1s +1s" does. (Handle drags are absolute, so they'd survive either way.)
   */
  function editClip(fn: (cur: ClipSelection) => ClipSelection) {
    setClipSel((cur) => (cur ? fn(cur) : cur));
    // Any edit invalidates whatever the last attempt said.
    setClipError(null);
    setClipState("idle");
  }

  async function downloadClip() {
    if (!clipSel) return;
    setClipState("preparing");
    setClipError(null);
    try {
      const url = await signedClipExportUrl(frigateExportName, clipSel.startMs, clipSel.endMs);
      if (!url) throw new Error("Couldn't authorise the download. Check the connection to HA.");

      // Probe before handing the URL over. A bare `<a download>` cannot see a failure: a 400 lands
      // as a silently-failed entry in the browser's download shelf with nothing the app can catch.
      // Frigate checks its recordings table before spawning ffmpeg, so the failure case is cheap.
      const probe = new AbortController();
      const res = await fetch(url, { signal: probe.signal });
      if (!res.ok) {
        let message = `Export failed (${res.status}).`;
        try {
          const body = (await res.json()) as { message?: string };
          if (body?.message) message = body.message;
        } catch {
          // Non-JSON error body — the status line is all we have to say.
        }
        throw new Error(message);
      }

      const fileName = clipFileName(cameraName, clipSel);
      const a = document.createElement("a");
      a.download = fileName;

      if (isSameOrigin(url)) {
        // Preferred path, and the deployed one (the app is served by the nginx pod that proxies
        // HA). Navigating hands the transfer to the browser, so a 300 MB export never sits in
        // memory and gets the native download UI. Costs one extra ffmpeg spin-up, which aborting
        // the probe cuts short.
        probe.abort();
        a.href = url;
        a.click();
      } else {
        // Settings can point straight at HA instead of at the proxy. Cross-origin, the `download`
        // attribute is IGNORED — and since Frigate sets no `Content-Disposition`, the browser
        // would open the mp4 in a tab instead of saving it. So stream the response we already have
        // into a blob and download that, which keeps the filename. Memory cost is real but bounded
        // by MAX_CLIP_MS, and it is the only way this configuration saves a file at all.
        const blob = await res.blob();
        const objectUrl = URL.createObjectURL(blob);
        a.href = objectUrl;
        a.click();
        URL.revokeObjectURL(objectUrl);
      }
      setClipState("started");
    } catch (err) {
      setClipState("failed");
      setClipError(err instanceof Error ? err.message : "Export failed.");
    }
  }

  return (
    <div className="space-y-md">
      {/* ONE row that wraps only when it has to.
          A non-wrapping row can't hold this many controls at phone width: each
          child shrinks to its minimum and labels end up wrapping one character
          per line rather than clipping. But forcing two rows unconditionally is
          also wrong — it costs a row of height on every camera and pushed the
          transport bar off-screen. `flex-wrap` + `shrink-0` gives one line when
          the set fits and extra lines only when it doesn't, which matters
          because the set varies per camera (Move only for PTZ, Low/High only
          with a sub stream, Talk only for Ring, Siren where one exists). */}
      <div className="flex flex-wrap items-center gap-sm">
          <CameraSwitcher cameras={cameras} current={camera} onSelect={onSelectCamera} />
          {isLive && ptz && (
            <button
              type="button"
              onClick={() => setShowPtz((s) => !s)}
              aria-pressed={showPtz}
              aria-label={showPtz ? "Hide camera controls" : "Move camera"}
              className={[
                "flex items-center gap-xs rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
                showPtz ? "bg-panel-high text-ink" : "bg-panel text-ink-dim hover:text-ink",
              ].join(" ")}
            >
              <Move size={14} />
              Move
            </button>
          )}
          {isLive && hasSubStream && <QualityToggle quality={quality} onChange={setQuality} />}
          <MuteButton muted={muted} onToggle={() => setMuted((m) => !m)} />
          <SnapshotButton
            snapshotUrl={snapshotUrl(camera.snapshotEntity)}
            cameraName={cameraName}
          />
          {/* The one and only gate. Frigate is the only backend that can cut an arbitrary range:
              Ring exposes whole pre-signed event clips that expire in ~15 min and cannot be
              trimmed, so offering a range selector there would promise something it can't do.
              Hidden while live too — there is nothing to export from the future. */}
          {clipMode && (
            <button
              type="button"
              onClick={() =>
                setClipSel((cur) => (cur ? null : defaultSelection(headTime, clipBounds)))
              }
              aria-pressed={clipSel !== null}
              aria-label={clipSel ? "Cancel clip export" : "Export a clip"}
              className={[
                "flex items-center gap-xs rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
                clipSel ? "bg-panel-high text-ink" : "bg-panel text-ink-dim hover:text-ink",
              ].join(" ")}
            >
              <Scissors size={14} />
              Clip
            </button>
          )}
          {/* Live only: TalkButton latches the mic open and unmounting it is what
              closes the session, so it must never outlive the live view. */}
          {canReachSpeaker && isLive && <TalkButton src={cameraName} />}
          {camera.sirenSwitchId && <SirenButton entityId={camera.sirenSwitchId} />}
          {/* ml-auto pushes the status to the right while everything shares a
              line, and simply trails the group once the row wraps. */}
          <span
            className={[
              "ml-auto flex shrink-0 items-center gap-xs whitespace-nowrap caption-label",
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

      {/* Wraps the WHOLE player rather than each source: live, recorded and the placeholder all
          zoom identically, and there is one gesture implementation instead of three. */}
      <ZoomableFrame>
      {isLive ? (
        <LivePlayer
          entity={camera.liveEntity}
          // Unconditional: go2rtc serves whatever streams it's configured for, and
          // that is no longer "Ring cameras only" — the Reolink main stream is one.
          // `go2rtcMaybeAvailable` already declines unknown streams off the fetched
          // stream list, so the gate belongs there, not in a per-backend guess here.
          // Quality=Low swaps in the `_sub` stream (only offered when go2rtc lists it).
          go2rtcSrc={liveGo2rtcSrc}
          muted={muted}
        />
      ) : recordingSrc ? (
        <HlsPlayer
          src={recordingSrc}
          muted={muted}
          paused={paused}
          // Only the demo/no-NVR source loops — it hands back the same bundled clip
          // for every seek. A real recording is finite and must end where it ends.
          loop={!hasRealRecordings(backend)}
          seekSeconds={seekSeconds}
          // Duration-learning is a Ring mechanic: it refines an open-ended
          // (`endMs: null`) selector event's span. Frigate events carry real ends.
          onDuration={isRing ? onDuration : undefined}
          onError={hasRealRecordings(backend) ? onPlaybackError : undefined}
          // Frigate VOD only: its nested manifest needs a Bearer token a URL signature cannot
          // cover. Ring's URLs are pre-signed by Ring and must NOT carry an HA token.
          authToken={isRing ? null : mediaAuthToken}
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
      </ZoomableFrame>

      {/* Live only: moving the lens while watching recorded footage would re-aim
          the camera with no visible feedback. Unmounting on the way out is what
          guarantees any in-flight move is stopped (see PtzPad). */}
      {isLive && ptz && showPtz && <PtzPanel ptz={ptz} />}

      <Timeline24h
        events={displayEvents}
        footage={footageLane}
        startMs={window.start}
        endMs={window.end}
        playhead={playhead}
        onSeek={seek}
        onScrub={scrub}
        onLive={goLive}
        selection={clipMode ? clipSel : null}
        selectionBounds={clipBounds}
        onSelectionChange={(next) => editClip(() => next)}
      />

      {/* Between the timeline and the transport on purpose: tapping a chip
          already seeks, so the description follows the playhead with no new
          interaction to learn. */}
      <EventDescription event={describedEvent} />

      {/* Replaces the transport rather than stacking under it: the column is already tight at
          phone width (pushing the transport off-screen has happened before), and prev/next/play
          are not what you reach for while marking a range. */}
      {clipMode && clipSel ? (
        <ClipExportBar
          selection={clipSel}
          playheadMs={headTime}
          footage={footageLane}
          state={clipState}
          error={clipError}
          onNudge={(edge, delta) =>
            editClip((cur) => nudgeClip(cur, edge, delta, clipBounds))
          }
          onSetEdgeToPlayhead={(edge) =>
            editClip((cur) => setClipEdge(cur, edge, headTime, clipBounds))
          }
          onCancel={() => {
            setClipSel(null);
            setClipError(null);
            setClipState("idle");
          }}
          onDownload={() => void downloadClip()}
        />
      ) : (
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
      )}
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

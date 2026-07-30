import { useEffect, useState } from "react";
import { VideoOff } from "lucide-react";
import type { HassEntity } from "../lib/ha";
import { useHaBaseUrl } from "../store/entityStore";
import { streamUrl as fetchStreamUrl, supportsWebRtc } from "../store/connection";
import {
  streamUrl as mjpegUrl,
  snapshotUrlAt,
  snapshotUrl,
  canStreamWebRtc,
} from "../lib/cameraUrl";
import { go2rtcMaybeAvailable, go2rtcStreamsKnown, primeGo2rtcStreams } from "../lib/go2rtc";
import { HlsPlayer } from "./HlsPlayer";
import { WebRtcPlayer } from "./WebRtcPlayer";
import { Go2rtcPlayer } from "./Go2rtcPlayer";

/**
 * On-demand live view for one camera, with a graceful transport ladder:
 *
 *   go2rtc-direct (~1-2s) → HA WebRTC → HLS/video → MJPEG proxy → snapshot poll → dead
 *
 * The dedicated go2rtc path is tried first when go2rtc actually serves this
 * camera. `go2rtcSrc` is now given for EVERY camera — go2rtc fronts the Reolink
 * main stream as well as the native Ring sources — so which cameras it can serve
 * is decided by its own stream list (`go2rtcMaybeAvailable`, plus a session
 * circuit-breaker that skips the tier once media is known-unreachable, e.g.
 * before the §7c host forwarder is up), not by what kind of camera it is.
 * Then HA WebRTC when the camera is STREAM-capable
 * (or doesn't say — see `canStreamWebRtc`; modern HA dropped the old
 * `frontend_stream_type` attribute this used to gate on); otherwise the HLS/demo
 * video tier. Each tier steps down on error. Nothing streams in the background —
 * this only runs while mounted ("Tap to Go Live").
 */
type Mode = "go2rtc" | "webrtc" | "video" | "mjpeg" | "poll" | "dead";

export function LivePlayer({
  entity,
  go2rtcSrc,
}: {
  entity: HassEntity;
  /** go2rtc stream name (the HA camera base) for the direct low-latency tier. */
  go2rtcSrc?: string;
}) {
  const baseUrl = useHaBaseUrl();
  const mjpeg = mjpegUrl(entity, baseUrl);
  const hasSnapshot = snapshotUrl(entity, baseUrl) !== null;
  const canWebRtc = canStreamWebRtc(entity) && supportsWebRtc();
  // Wait for the stream list before betting on go2rtc. `go2rtcMaybeAvailable` is
  // optimistic until that list lands, which was harmless while `go2rtcSrc` was
  // passed for Ring cameras only — every camera that got here really did have a
  // stream. Now it's passed for every camera, so an un-primed optimistic "yes"
  // would send demo cameras (and any HA camera go2rtc doesn't serve) down a tier
  // that can only fail, costing a watchdog stall before the ladder steps down.
  // The fetch is same-origin and lands in milliseconds; it flips false→true once
  // per session, before the first frame.
  const [streamsKnown, setStreamsKnown] = useState(() => go2rtcStreamsKnown());
  const canGo2rtc = !!go2rtcSrc && streamsKnown && go2rtcMaybeAvailable(go2rtcSrc);
  const topMode = (): Mode => (canGo2rtc ? "go2rtc" : canWebRtc ? "webrtc" : "video");

  const [src, setSrc] = useState<string | null>(null);
  const [srcResolved, setSrcResolved] = useState(false);
  const [mode, setMode] = useState<Mode>(topMode);
  const [tick, setTick] = useState(0);

  // Warm the go2rtc stream-list cache so the tier can be gated accurately.
  useEffect(() => {
    if (!go2rtcSrc) return;
    let active = true;
    void primeGo2rtcStreams().then(() => active && setStreamsKnown(true));
    return () => {
      active = false;
    };
  }, [go2rtcSrc]);

  // Reset the ladder when the camera changes — or when the stream list first
  // arrives, which is what promotes a go2rtc-served camera up to that tier. That
  // transition happens once per session, within ms of mount and before any frame,
  // so it can't tear down an established stream.
  useEffect(() => {
    setSrc(null);
    setSrcResolved(false);
    setMode(topMode());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entity.entity_id, go2rtcSrc, streamsKnown]);

  // Resolve the HLS/demo stream URL only once the "video" tier is actually
  // active — NOT eagerly on mount. `camera/stream` makes HA spin up an HLS
  // pipeline, which on a battery camera wakes it / competes for its single live
  // session in parallel with the WebRTC negotiation above it on the ladder.
  useEffect(() => {
    if (mode !== "video" || srcResolved) return;
    let active = true;
    fetchStreamUrl(entity.entity_id)
      .then((url) => {
        if (!active) return;
        setSrc(url);
        setSrcResolved(true);
      })
      .catch(() => {
        if (!active) return;
        setSrc(null);
        setSrcResolved(true);
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, srcResolved, entity.entity_id]);

  // Once the stream URL resolves to nothing while we're on the video tier, step down.
  useEffect(() => {
    if (mode === "video" && srcResolved && !src) stepDownFrom("video");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, srcResolved, src]);

  // Fast snapshot polling once we've fallen back to the poll tier.
  useEffect(() => {
    if (mode !== "poll") return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [mode]);

  /** Advance the ladder one rung past `from`, skipping tiers we can't render. */
  function stepDownFrom(from: Mode) {
    if (from === "go2rtc") setMode(canWebRtc ? "webrtc" : "video");
    else if (from === "webrtc") setMode("video");
    else if (from === "video") setMode(mjpeg ? "mjpeg" : hasSnapshot ? "poll" : "dead");
    else if (from === "mjpeg") setMode(hasSnapshot ? "poll" : "dead");
    else setMode("dead");
  }

  if (mode === "dead") {
    return (
      <div className="flex aspect-video w-full flex-col items-center justify-center gap-sm rounded-lg bg-panel">
        <VideoOff className="text-ink-faint" size={40} />
        <span className="font-body text-body text-ink-dim">Live view unavailable</span>
      </div>
    );
  }

  if (mode === "go2rtc" && go2rtcSrc) {
    return (
      <Go2rtcPlayer
        src={go2rtcSrc}
        poster={snapshotUrl(entity, baseUrl) ?? undefined}
        onFail={() => stepDownFrom("go2rtc")}
      />
    );
  }

  if (mode === "webrtc") {
    return (
      <WebRtcPlayer
        entityId={entity.entity_id}
        poster={snapshotUrl(entity, baseUrl) ?? undefined}
        onFail={() => stepDownFrom("webrtc")}
      />
    );
  }

  if (mode === "video") {
    if (!src) {
      // Resolving the stream URL — hold the frame with the latest snapshot.
      return (
        <img
          src={snapshotUrl(entity, baseUrl) ?? undefined}
          alt="Live camera view"
          className="aspect-video w-full rounded-lg bg-black object-contain"
        />
      );
    }
    return (
      <HlsPlayer
        src={src}
        poster={snapshotUrl(entity, baseUrl) ?? undefined}
        loop
        onError={() => stepDownFrom("video")}
      />
    );
  }

  const imgSrc =
    mode === "mjpeg" ? (mjpeg as string) : snapshotUrlAt(entity, tick, baseUrl);

  return (
    <img
      src={imgSrc ?? undefined}
      alt="Live camera view"
      onError={() => stepDownFrom(mode)}
      className="aspect-video w-full rounded-lg bg-black object-contain"
    />
  );
}

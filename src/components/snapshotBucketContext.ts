import { createContext, useContext, useEffect } from "react";

/**
 * Snapshot cache-buster "buckets" — counters appended to a camera's snapshot URL
 * so an `<img>` refetches a fresh frame. The provider lives in `SnapshotBucket.tsx`;
 * this module holds the context + hooks so the component file stays a
 * component-only module (react-refresh friendly).
 *
 * TWO counters, because the two camera backends want different refresh policies:
 *
 * - `shared` ticks on one app-wide beat while the app is visible. Every camera
 *   rides it, so tiles refresh together instead of on N independent timers.
 * - `onOpen` is that same beat PLUS an extra tick every time the app becomes
 *   visible again. Frigate cameras only.
 *
 * Why Frigate-only: Ring's proxy is rate-limited, and the
 * `hawksnest_ring_snapshot_policy` automation deliberately pins battery cameras to a
 * 300s snapshot interval — so forcing a Ring refetch on every app open spends a
 * metered request to receive *the same image*. Frigate cameras are local and
 * always-on and their snapshot is current the instant it is asked for, so a refetch
 * on open is both cheap and the only way to avoid opening the app on a frame from
 * whenever it was last foregrounded.
 *
 * "Always-on" is the premise, so a Frigate camera that ISN'T — a battery Reolink behind
 * a Home Hub, parked off in Frigate until its PIR fires — rides the `shared` beat like
 * a Ring camera: while it sleeps, Frigate serves the same stale frame (or its error
 * image) on every fetch, and the on-open tick would just buy that again.
 */
export interface SnapshotBuckets {
  /** Shared ~10s beat while visible. All cameras. */
  shared: number;
  /** The shared beat, plus one tick per app-open/foreground. Frigate cameras only. */
  onOpen: number;
}

/**
 * Zeroes are only for consumers rendered outside a provider (tests, Storybook). The
 * real provider seeds both from the clock so a cold load cannot reuse the previous
 * session's URL — see `SnapshotBucket.tsx`.
 */
export const SnapshotBucketContext = createContext<SnapshotBuckets>({
  shared: 0,
  onOpen: 0,
});

export function useSnapshotBuckets(): SnapshotBuckets {
  return useContext(SnapshotBucketContext);
}

/**
 * The bucket a camera should bust its cache with.
 *
 * @param isFrigate whether Frigate records this camera (`isFrigateCamera`) AND it is
 *   always-on — pass `isFrigate && !battery`. Those cameras additionally refresh every time
 *   the app is opened; everything else rides the shared beat.
 */
export function useSnapshotBucket(isFrigate: boolean): number {
  const { shared, onOpen } = useSnapshotBuckets();
  return isFrigate ? onOpen : shared;
}

/**
 * Pauses the shared beat while something covers the camera tiles — the open-camera lightbox.
 * Measured 2026-10-30: with the player open, all 14 tiles (~700 KB) still refetched on every
 * beat, behind an opaque backdrop, competing with the very video the user opened. Tiles nobody
 * can see don't need fresh frames; one catch-up tick on release brings them current.
 *
 * Kept in its OWN context (a stable object) so registering a pause never re-renders consumers
 * of the bucket counters, which change every beat.
 */
export interface SnapshotPauseController {
  /** Hold the beat; returns the release. Holds nest — the beat resumes when the last releases. */
  hold: () => () => void;
}

export const SnapshotPauseContext = createContext<SnapshotPauseController>({
  hold: () => () => {},
});

/** Pause snapshot refreshes for as long as `active` is true and the caller is mounted. */
export function useSnapshotPause(active: boolean): void {
  const { hold } = useContext(SnapshotPauseContext);
  useEffect(() => (active ? hold() : undefined), [active, hold]);
}

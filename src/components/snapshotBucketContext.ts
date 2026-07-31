import { createContext, useContext } from "react";

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
 * @param isFrigate whether Frigate records this camera (`isFrigateCamera`). Frigate
 *   cameras additionally refresh every time the app is opened.
 */
export function useSnapshotBucket(isFrigate: boolean): number {
  const { shared, onOpen } = useSnapshotBuckets();
  return isFrigate ? onOpen : shared;
}

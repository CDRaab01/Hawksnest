import { createContext, useContext } from "react";

/**
 * Current snapshot "bucket" — a counter that ticks on a shared interval while
 * the tab is visible. Camera tiles append it to their snapshot URL as a
 * cache-buster so they all refetch a fresh frame on the same beat. The provider
 * lives in `SnapshotBucket.tsx`; this module holds the context + hook so the
 * component file stays a component-only module (react-refresh friendly).
 */
export const SnapshotBucketContext = createContext(0);

export function useSnapshotBucket(): number {
  return useContext(SnapshotBucketContext);
}

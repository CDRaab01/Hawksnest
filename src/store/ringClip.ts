import { callService, streamUrl } from "./connection";
import { useEntityStore } from "./entityStore";
import { ringRecordingMissing, ringRecordingUrl } from "../lib/ringEvents";

/**
 * How long ring-mqtt gets to publish the selected event's recording URL. It has
 * to round-trip Ring's cloud (history lookup + a signed media URL, sometimes a
 * transcode), which is slow but not minutes-slow.
 */
export const RING_CLIP_TIMEOUT_MS = 20_000;

/**
 * Resolve a playable URL for one ring-mqtt recorded event.
 *
 * ring-mqtt 5.x models recorded playback as: pick an option on
 * `select.<base>_event_select` → it fetches Ring's signed cloud recording and
 * republishes the selector with a `recordingUrl` attribute. There is **no**
 * `camera.<base>_event` entity to ask HA for a stream (that was ring-mqtt 4.x;
 * `eventStreamId` keeps that path working where such an entity does exist).
 *
 * Resolves null when nothing playable turns up — the caller shows an honest,
 * retryable failure rather than a stuck loader.
 */
export async function resolveRingClipUrl({
  selectId,
  option,
  eventStreamId = null,
  timeoutMs = RING_CLIP_TIMEOUT_MS,
}: {
  selectId: string;
  option: string;
  eventStreamId?: string | null;
  timeoutMs?: number;
}): Promise<string | null> {
  const before = useEntityStore.getState().entities[selectId];
  // Already the active option? Then its published URL is the answer as-is —
  // re-selecting won't change it, so there's no "did it update yet" to wait for.
  const alreadySelected = before?.state === option;
  const urlBefore = ringRecordingUrl(before);

  try {
    await callService("select", "select_option", { entity_id: selectId, option });
  } catch {
    /* selecting failed (option rotated out) — whatever is published may still play */
  }

  // Legacy ring-mqtt (4.x): a real `camera.<base>_event` entity HA can stream.
  if (eventStreamId) return streamUrl(eventStreamId);

  return waitForRecordingUrl(selectId, option, { alreadySelected, urlBefore, timeoutMs });
}

/**
 * Wait for the selector to report `option` **with a recording URL that belongs to
 * it**. ring-mqtt publishes state and attributes together (it awaits the URL
 * before publishing), but HA delivers them as two updates — requiring the URL to
 * have changed keeps the sub-millisecond window from playing the previous clip.
 *
 * On the deadline we take whatever is published for the current selection rather
 * than failing outright: two options can map to the same Ring event (`Motion 1` /
 * `Person 1`), in which case the URL legitimately never changes.
 */
function waitForRecordingUrl(
  selectId: string,
  option: string,
  {
    alreadySelected,
    urlBefore,
    timeoutMs,
  }: { alreadySelected: boolean; urlBefore: string | null; timeoutMs: number },
): Promise<string | null> {
  /** null = terminal failure, string = playable, undefined = keep waiting. */
  const read = (): string | null | undefined => {
    const entity = useEntityStore.getState().entities[selectId];
    if (entity?.state !== option) return undefined;
    if (ringRecordingMissing(entity)) return null;
    const url = ringRecordingUrl(entity);
    if (!url) return undefined;
    return alreadySelected || url !== urlBefore ? url : undefined;
  };

  const settled = read();
  if (settled !== undefined) return Promise.resolve(settled);

  return new Promise((resolve) => {
    let done = false;
    const finish = (url: string | null) => {
      if (done) return;
      done = true;
      unsubscribe();
      clearTimeout(timer);
      resolve(url);
    };
    const unsubscribe = useEntityStore.subscribe(() => {
      const next = read();
      if (next !== undefined) finish(next);
    });
    const timer = setTimeout(() => {
      const entity = useEntityStore.getState().entities[selectId];
      finish(entity?.state === option ? ringRecordingUrl(entity) : null);
    }, timeoutMs);
  });
}

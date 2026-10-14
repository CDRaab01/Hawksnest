import { useCallback, useState, type SyntheticEvent } from "react";

/**
 * Camera-picture aspect handling.
 *
 * Hawksnest historically forced every camera frame into a 16:9 box
 * (`aspect-video`). That is wrong for two shapes in the fleet: the Reolink
 * dual-lens outdoor cameras stitch two lenses into one ~32:9 panorama
 * (`1536x432`), and the Reolink doorbell is 4:3. A 16:9 box letterboxes the
 * panorama into a thin strip (the player) or crops its two lenses off entirely
 * (the wall's `object-cover`).
 *
 * These helpers let a surface render at the media's TRUE aspect ratio instead —
 * matching how Reolink itself presents a Duo: one wide picture you pinch-zoom
 * into (the `ZoomableFrame` already provides the zoom). The ratio is measured
 * from the media once it loads, so no per-camera resolution table is needed and
 * a 4:3 doorbell is corrected by the same code path.
 */

/** Fallback ratio (width/height) until the media reports real dimensions. */
export const DEFAULT_ASPECT = 16 / 9;

/**
 * A stitched dual-lens panorama is ~3.56:1; a normal camera is 1.33–1.78:1.
 * 2.2 sits clearly between the two families, so anything at least this wide is
 * treated as a panorama that should span its row rather than sit cropped in a
 * grid cell.
 */
export const WIDE_ASPECT_THRESHOLD = 2.2;

/**
 * width / height, or null when either dimension is unknown — a `<video>` reports
 * 0×0 before `loadedmetadata` and an `<img>` before `load`, and 0 must never
 * become an aspect ratio.
 */
export function aspectFromDimensions(width: number, height: number): number | null {
  return width > 0 && height > 0 ? width / height : null;
}

/** Whether a ratio is that of a dual-lens panorama (see WIDE_ASPECT_THRESHOLD). */
export function isWideAspect(ratio: number): boolean {
  return ratio >= WIDE_ASPECT_THRESHOLD;
}

export interface MediaAspect {
  /** Best-known ratio (width/height); `DEFAULT_ASPECT` until the media measures. */
  ratio: number;
  /** True once the measured ratio is that of a dual-lens panorama. */
  isWide: boolean;
  /** Inline style pinning the element (or its box) to the measured ratio. */
  style: { aspectRatio: string };
  /** `<video onLoadedMetadata>` handler — reads `videoWidth`/`videoHeight`. */
  onVideoMeta: (e: SyntheticEvent<HTMLVideoElement>) => void;
  /** `<img onLoad>` handler — reads `naturalWidth`/`naturalHeight`. */
  onImageLoad: (e: SyntheticEvent<HTMLImageElement>) => void;
}

/**
 * Tracks a media element's intrinsic aspect ratio so a surface can size itself to
 * the picture instead of a hardcoded 16:9. Attach `onVideoMeta`/`onImageLoad` to
 * the element and spread `style` onto the element (or its aspect box); read
 * `isWide` to decide layout (e.g. a full-width wall tile). See the module doc.
 */
export function useMediaAspect(initial: number = DEFAULT_ASPECT): MediaAspect {
  const [ratio, setRatio] = useState(initial);
  const onVideoMeta = useCallback((e: SyntheticEvent<HTMLVideoElement>) => {
    const a = aspectFromDimensions(e.currentTarget.videoWidth, e.currentTarget.videoHeight);
    if (a) setRatio(a);
  }, []);
  const onImageLoad = useCallback((e: SyntheticEvent<HTMLImageElement>) => {
    const a = aspectFromDimensions(e.currentTarget.naturalWidth, e.currentTarget.naturalHeight);
    if (a) setRatio(a);
  }, []);
  return {
    ratio,
    isWide: isWideAspect(ratio),
    style: { aspectRatio: String(ratio) },
    onVideoMeta,
    onImageLoad,
  };
}

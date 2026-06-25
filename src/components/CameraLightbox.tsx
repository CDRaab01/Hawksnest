import { useEffect } from "react";
import { Link } from "react-router-dom";
import { X, ExternalLink } from "lucide-react";
import type { HassEntity } from "../lib/ha";
import { resolveName } from "../lib/resolve";
import { overrides } from "../config/overrides";
import { LivePlayer } from "./LivePlayer";

interface Props {
  entity: HassEntity;
  onClose: () => void;
}

/**
 * Full-screen on-tap live view. Mounts the MJPEG `LivePlayer` only while open,
 * closes on backdrop click or Escape, and offers a jump to the camera's full
 * entity screen (history + attributes).
 */
export function CameraLightbox({ entity, onClose }: Props) {
  const name = resolveName(entity, overrides);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${name} live view`}
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-lg backdrop-blur"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-4xl space-y-md"
      >
        <div className="flex items-center gap-md">
          <span className="h-2 w-2 rounded-full bg-recovery" />
          <span className="font-display text-headline text-ink">{name}</span>
          <div className="ml-auto flex items-center gap-sm">
            <Link
              to={`/entity/${encodeURIComponent(entity.entity_id)}`}
              aria-label="Open camera details"
              className="rounded-sm p-sm text-ink-dim transition-colors duration-fast hover:text-ink"
            >
              <ExternalLink size={20} />
            </Link>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close live view"
              className="rounded-sm p-sm text-ink-dim transition-colors duration-fast hover:text-ink"
            >
              <X size={22} />
            </button>
          </div>
        </div>
        <LivePlayer entity={entity} />
      </div>
    </div>
  );
}

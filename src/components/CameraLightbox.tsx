import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { X, ExternalLink } from "lucide-react";
import type { LogicalCamera } from "../lib/cameraModel";
import { useLogicalCameras } from "../store/entityStore";
import { viewTransitionNameFor } from "../store/cameraOverlay";
import { CameraPlayer } from "./camera/CameraPlayer";

interface Props {
  camera: LogicalCamera;
  onClose: () => void;
}

/**
 * Full-screen on-tap camera view. Mounts the Ring-style `CameraPlayer` (live +
 * timeline scrubber + transport + in-player switcher) only while open, closes on
 * backdrop click or Escape, and offers a jump to the camera's full entity screen.
 * Tracks the switched-to camera locally so the player can change feeds without
 * closing.
 */
export function CameraLightbox({ camera, onClose }: Props) {
  const cameras = useLogicalCameras();
  const [activeId, setActiveId] = useState(camera.id);
  const active = cameras.find((c) => c.id === activeId) ?? camera;

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
      aria-label={`${active.name} camera view`}
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-lg backdrop-blur"
    >
      {/* The player claims the open camera's transition name, so the wall tile
          visually expands into it (and collapses back on close). */}
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-4xl"
        style={{ viewTransitionName: viewTransitionNameFor(active.id) }}
      >
        <div className="mb-md flex items-center justify-end gap-sm">
          <Link
            to={`/entity/${encodeURIComponent(active.liveEntity.entity_id)}`}
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
        <CameraPlayer
          camera={active}
          cameras={cameras.length > 0 ? cameras : [active]}
          onSelectCamera={(c) => setActiveId(c.id)}
        />
      </div>
    </div>
  );
}

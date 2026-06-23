import { Camera } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import type { CardProps } from "./types";

/**
 * Camera tile. No live stream in Phase 0 — a styled placeholder with an IR-ish
 * gradient stands in for the Ring feed so the Security scene reads correctly.
 */
export function CameraTile({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const aspect = density === "compact" ? "aspect-video" : "aspect-[4/3]";

  return (
    <PanelCard className="overflow-hidden">
      <div
        className={[
          aspect,
          "relative w-full bg-[radial-gradient(120%_120%_at_20%_0%,#2a2f37_0%,#0e1116_70%)]",
        ].join(" ")}
      >
        <div className="absolute inset-0 flex items-center justify-center">
          <Camera className="text-ink-faint" size={32} />
        </div>
        <div className="absolute left-md top-md flex items-center gap-xs">
          <span className="h-2 w-2 rounded-full bg-streak" />
          <span className="caption-label text-ink-dim">Live</span>
        </div>
        <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/70 to-transparent p-md">
          <span className="font-body text-body text-white">{name}</span>
        </div>
      </div>
    </PanelCard>
  );
}

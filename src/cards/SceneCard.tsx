import { useState } from "react";
import { Play, Sparkles } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import { relativeTime } from "../lib/relativeTime";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

/**
 * `scene` card — one button that runs the scene.
 *
 * Here for the same reason as `SwitchCard`: `density.ts` classes `scene` as a control domain, so
 * a scene has always rendered a comfortable, control-sized card, and without an entry in
 * `cards.ts` that card was the read-only `GenericCard`. Worse than a switch's version of the
 * problem, because a scene's `state` is an ISO timestamp of its last activation — so the card
 * showed a raw datetime where a control belonged.
 *
 * A scene has no on/off, only "run it", so this is a button and not a toggle. There is no
 * optimistic state to fake either: the confirmation is the room changing, and HA's echo is the
 * new `state` timestamp, which the card shows as "Ran 2m ago".
 *
 * Scenes are in `NON_DEVICE_DOMAINS`, so this never appears in the Devices hub — only in room
 * detail and entity detail, which is where a scene is actually reached for.
 */
export function SceneCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const compact = density === "compact";
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // HA sets a scene's state to the ISO time it last ran; `unknown` means "not since HA started".
  const lastRun = Date.parse(entity.state);
  const ranAt = Number.isFinite(lastRun) ? lastRun : null;

  function activate() {
    setError(null);
    setBusy(true);
    void callService("scene", "turn_on", { entity_id: entity.entity_id })
      .catch(() => setError("Couldn't run the scene."))
      .finally(() => setBusy(false));
  }

  return (
    <PanelCard className={compact ? "p-md" : "p-lg"}>
      <div className="flex items-center gap-md">
        <Sparkles className="shrink-0 text-strength" size={compact ? 22 : 26} />
        <div className="min-w-0">
          <div
            className={[
              "truncate font-body text-ink",
              compact ? "text-body" : "text-body-lg",
            ].join(" ")}
          >
            {name}
          </div>
          <div
            className={[
              "font-body text-ink-dim",
              compact ? "text-caption" : "text-body",
            ].join(" ")}
          >
            {ranAt ? `Ran ${relativeTime(ranAt)}` : "Not run yet"}
          </div>
          {error && <div className="font-body text-caption text-streak">{error}</div>}
        </div>
        <button
          type="button"
          aria-label={`Run ${name}`}
          disabled={busy}
          onClick={activate}
          className="ml-auto flex shrink-0 items-center gap-xs rounded-full bg-panel-high px-lg py-sm font-body text-body text-ink transition-colors duration-fast hover:bg-panel disabled:opacity-40"
        >
          <Play size={14} />
          {busy ? "Running…" : "Run"}
        </button>
      </div>
    </PanelCard>
  );
}

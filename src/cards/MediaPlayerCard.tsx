import { Play, Pause, SkipForward, SkipBack, Clapperboard } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

/**
 * Media player card. Play/pause + track skip via media_player.* services; HA's
 * echo reconciles the store. Tinted on the effort channel (the primary-action
 * hue). Shows the current title/artist when the player exposes them.
 */
export function MediaPlayerCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const playing = entity.state === "playing";
  const idle =
    entity.state === "off" ||
    entity.state === "idle" ||
    entity.state === "standby" ||
    entity.state === "unavailable";
  const title = entity.attributes.media_title as string | undefined;
  const artist = entity.attributes.media_artist as string | undefined;

  function svc(service: string) {
    void callService("media_player", service, { entity_id: entity.entity_id });
  }

  const nowPlaying = title
    ? artist
      ? `${title} — ${artist}`
      : title
    : idle
      ? "Idle"
      : entity.state;

  return (
    <PanelCard tint={playing ? "effort" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        <Clapperboard className={playing ? "text-effort" : "text-ink-faint"} size={26} />
        <div className="min-w-0">
          <div className="truncate font-body text-body-lg text-ink">{name}</div>
          <div className="truncate font-body text-body text-ink-dim">{nowPlaying}</div>
        </div>
      </div>
      <div className="mt-lg flex items-center justify-center gap-lg">
        <button
          type="button"
          aria-label="Previous track"
          disabled={idle}
          onClick={() => svc("media_previous_track")}
          className="text-ink-dim transition-transform duration-fast active:scale-90 disabled:opacity-30 hover:text-ink"
        >
          <SkipBack size={22} />
        </button>
        <button
          type="button"
          aria-label={playing ? "Pause" : "Play"}
          disabled={idle}
          onClick={() => svc("media_play_pause")}
          className="inline-flex h-12 w-12 items-center justify-center rounded-full bg-effort-dim text-effort transition-transform duration-fast active:scale-90 disabled:opacity-30"
        >
          {playing ? <Pause size={24} /> : <Play size={24} />}
        </button>
        <button
          type="button"
          aria-label="Next track"
          disabled={idle}
          onClick={() => svc("media_next_track")}
          className="text-ink-dim transition-transform duration-fast active:scale-90 disabled:opacity-30 hover:text-ink"
        >
          <SkipForward size={22} />
        </button>
      </div>
    </PanelCard>
  );
}

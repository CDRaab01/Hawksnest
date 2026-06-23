import { Link } from "react-router-dom";
import {
  ArrowLeft,
  ChevronDown,
  ChevronUp,
  Eye,
  EyeOff,
  RotateCcw,
  Star,
} from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { SectionHeader } from "../components/SectionHeader";
import { PulseButton } from "../components/PulseButton";
import { overrides } from "../config/overrides";
import { resolveIcon, resolveName } from "../lib/resolve";
import { groupByArea } from "../lib/areas";
import type { HassEntity } from "../lib/ha";
import { useEntityStore } from "../store/entityStore";
import {
  useFavorites,
  useHidden,
  usePrefsStore,
} from "../store/prefsStore";

/** A small square icon-button used for the per-row pin/hide/reorder actions. */
function IconAction({
  label,
  onClick,
  disabled = false,
  active = false,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      disabled={disabled}
      onClick={onClick}
      className={[
        "inline-flex h-9 w-9 items-center justify-center rounded-sm border transition-colors duration-fast",
        active
          ? "border-effort/40 bg-effort-dim text-effort"
          : "border-hairline text-ink-dim hover:text-ink",
        "disabled:opacity-30 disabled:hover:text-ink-dim",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

/** Icon + resolved label for one entity (shared by both sections). */
function EntityLabel({ entity }: { entity: HassEntity }) {
  const Icon = resolveIcon(entity, overrides);
  return (
    <div className="flex min-w-0 items-center gap-sm">
      <Icon size={18} className="shrink-0 text-ink-dim" />
      <span className="truncate font-body text-body text-ink">
        {resolveName(entity, overrides)}
      </span>
    </div>
  );
}

/**
 * Customize (Phase 3) — the in-app personalization editor: reorder/unpin the
 * Home favorites and pin/hide any entity. All edits write through `prefsStore`
 * to localStorage immediately, so there is no explicit "save".
 */
export function CustomizeScreen() {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntityStore((s) => s.areas);
  const favorites = useFavorites();
  const hidden = useHidden();
  const { togglePin, toggleHidden, moveFavorite, resetAll } = usePrefsStore();

  const hiddenSet = new Set(hidden);
  const favoriteSet = new Set(favorites);
  // Full (unfiltered) grouping — the editor lists hidden entities too so they
  // can be un-hidden. The screens use the hidden-filtered grouping instead.
  const groups = groupByArea(Object.values(entities), areas);
  const pinned = favorites
    .map((id) => entities[id])
    .filter((e): e is HassEntity => Boolean(e));

  return (
    <div className="space-y-xl">
      <Link
        to="/"
        className="inline-flex items-center gap-xs text-body text-ink-dim transition-colors duration-fast hover:text-ink"
      >
        <ArrowLeft size={16} /> Home
      </Link>

      <section className="space-y-md">
        <SectionHeader label="Favorites" channel="effort" />
        {pinned.length > 0 ? (
          <PanelCard className="divide-y divide-hairline">
            {pinned.map((entity, i) => (
              <div
                key={entity.entity_id}
                className="flex items-center gap-md px-lg py-md"
              >
                <EntityLabel entity={entity} />
                <div className="ml-auto flex shrink-0 items-center gap-xs">
                  <IconAction
                    label="Move up"
                    onClick={() => moveFavorite(entity.entity_id, -1)}
                    disabled={i === 0}
                  >
                    <ChevronUp size={18} />
                  </IconAction>
                  <IconAction
                    label="Move down"
                    onClick={() => moveFavorite(entity.entity_id, 1)}
                    disabled={i === pinned.length - 1}
                  >
                    <ChevronDown size={18} />
                  </IconAction>
                  <IconAction
                    label="Unpin"
                    active
                    onClick={() => togglePin(entity.entity_id)}
                  >
                    <Star size={18} className="fill-current" />
                  </IconAction>
                </div>
              </div>
            ))}
          </PanelCard>
        ) : (
          <PanelCard className="p-lg">
            <p className="font-body text-body text-ink-dim">
              No favorites pinned. Tap the star on any device below to pin it to
              Home.
            </p>
          </PanelCard>
        )}
      </section>

      <section className="space-y-md">
        <SectionHeader label="All devices" channel="recovery" />
        {groups.map((group) => (
          <div key={group.area} className="space-y-sm">
            <div className="caption-label px-xs">{group.area}</div>
            <PanelCard className="divide-y divide-hairline">
              {group.entities.map((entity) => {
                const isHidden = hiddenSet.has(entity.entity_id);
                const isPinned = favoriteSet.has(entity.entity_id);
                return (
                  <div
                    key={entity.entity_id}
                    className={[
                      "flex items-center gap-md px-lg py-md",
                      isHidden ? "opacity-40" : "",
                    ].join(" ")}
                  >
                    <EntityLabel entity={entity} />
                    <div className="ml-auto flex shrink-0 items-center gap-xs">
                      <IconAction
                        label={isPinned ? "Unpin from Home" : "Pin to Home"}
                        active={isPinned}
                        onClick={() => togglePin(entity.entity_id)}
                      >
                        <Star
                          size={18}
                          className={isPinned ? "fill-current" : ""}
                        />
                      </IconAction>
                      <IconAction
                        label={isHidden ? "Show in areas" : "Hide from areas"}
                        active={isHidden}
                        onClick={() => toggleHidden(entity.entity_id)}
                      >
                        {isHidden ? <EyeOff size={18} /> : <Eye size={18} />}
                      </IconAction>
                    </div>
                  </div>
                );
              })}
            </PanelCard>
          </div>
        ))}
      </section>

      <section className="space-y-md">
        <SectionHeader label="Reset" channel="streak" />
        <PanelCard className="flex flex-wrap items-center gap-md p-lg">
          <p className="min-w-0 flex-1 font-body text-body text-ink-dim">
            Forget all customizations and return to the default favorites with
            nothing hidden.
          </p>
          <PulseButton variant="ghost" onClick={resetAll}>
            <RotateCcw size={16} /> Reset to defaults
          </PulseButton>
        </PanelCard>
      </section>
    </div>
  );
}

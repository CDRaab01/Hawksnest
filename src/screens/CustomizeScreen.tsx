import { Link } from "react-router-dom";
import {
  ArrowLeft,
  ChevronDown,
  ChevronUp,
  Eye,
  EyeOff,
  GripVertical,
  RotateCcw,
  Star,
} from "lucide-react";
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
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
 * One draggable favorite row. The grip is the drag handle (touch + pointer);
 * the up/down arrows remain as a keyboard/assistive fallback alongside DnD.
 */
function SortableFavoriteRow({
  entity,
  index,
  isLast,
  onUp,
  onDown,
  onUnpin,
}: {
  entity: HassEntity;
  index: number;
  isLast: boolean;
  onUp: () => void;
  onDown: () => void;
  onUnpin: () => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: entity.entity_id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={[
        "flex items-center gap-md bg-panel px-lg py-md",
        isDragging ? "rounded-sm border border-hairline-strong" : "",
      ].join(" ")}
    >
      <button
        type="button"
        aria-label={`Drag to reorder ${resolveName(entity, overrides)}`}
        className="-ml-xs cursor-grab touch-none text-ink-faint transition-colors duration-fast hover:text-ink active:cursor-grabbing"
        {...attributes}
        {...listeners}
      >
        <GripVertical size={18} />
      </button>
      <EntityLabel entity={entity} />
      <div className="ml-auto flex shrink-0 items-center gap-xs">
        <IconAction label="Move up" onClick={onUp} disabled={index === 0}>
          <ChevronUp size={18} />
        </IconAction>
        <IconAction label="Move down" onClick={onDown} disabled={isLast}>
          <ChevronDown size={18} />
        </IconAction>
        <IconAction label="Unpin" active onClick={onUnpin}>
          <Star size={18} className="fill-current" />
        </IconAction>
      </div>
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
  const { togglePin, toggleHidden, moveFavorite, reorderFavorites, resetAll } =
    usePrefsStore();

  // Pointer for touch/mouse drag; keyboard sensor for accessible reordering.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const hiddenSet = new Set(hidden);
  const favoriteSet = new Set(favorites);
  // Full (unfiltered) grouping — the editor lists hidden entities too so they
  // can be un-hidden. The screens use the hidden-filtered grouping instead.
  const groups = groupByArea(Object.values(entities), areas);
  const pinned = favorites
    .map((id) => entities[id])
    .filter((e): e is HassEntity => Boolean(e));
  const pinnedIds = pinned.map((e) => e.entity_id);

  function onDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const from = pinnedIds.indexOf(String(active.id));
    const to = pinnedIds.indexOf(String(over.id));
    if (from !== -1 && to !== -1) reorderFavorites(from, to);
  }

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
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragEnd={onDragEnd}
            >
              <SortableContext
                items={pinnedIds}
                strategy={verticalListSortingStrategy}
              >
                {pinned.map((entity, i) => (
                  <SortableFavoriteRow
                    key={entity.entity_id}
                    entity={entity}
                    index={i}
                    isLast={i === pinned.length - 1}
                    onUp={() => moveFavorite(entity.entity_id, -1)}
                    onDown={() => moveFavorite(entity.entity_id, 1)}
                    onUnpin={() => togglePin(entity.entity_id)}
                  />
                ))}
              </SortableContext>
            </DndContext>
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

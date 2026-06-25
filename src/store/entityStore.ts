import { useMemo } from "react";
import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type { AreaRegistry, HassEntity } from "../lib/ha";
import { groupByArea, type AreaGroup } from "../lib/areas";
import { useHidden } from "./prefsStore";

export type ConnectionStatus = "demo" | "connecting" | "connected" | "error";

interface EntityState {
  entities: Record<string, HassEntity>;
  areas: AreaRegistry;
  status: ConnectionStatus;
  error?: string;
  /** Replace the whole snapshot (initial load / full re-sync). */
  setSnapshot: (entities: Record<string, HassEntity>, areas: AreaRegistry) => void;
  /** Replace just the entity map (a live state push); leaves areas intact. */
  setEntities: (entities: Record<string, HassEntity>) => void;
  /** Replace just the area registry. */
  setAreas: (areas: AreaRegistry) => void;
  /** Merge a batch of entity updates (live state changes). */
  upsertEntities: (entities: HassEntity[]) => void;
  setStatus: (status: ConnectionStatus, error?: string) => void;
}

export const useEntityStore = create<EntityState>((set) => ({
  entities: {},
  areas: {},
  status: "connecting",
  setSnapshot: (entities, areas) => set({ entities, areas }),
  setEntities: (entities) => set({ entities }),
  setAreas: (areas) => set({ areas }),
  upsertEntities: (list) =>
    set((s) => {
      const entities = { ...s.entities };
      for (const e of list) entities[e.entity_id] = e;
      return { entities };
    }),
  setStatus: (status, error) => set({ status, error }),
}));

// --- selector hooks (subscribe to slices, not the whole store) ---

export const useEntity = (id: string): HassEntity | undefined =>
  useEntityStore((s) => s.entities[id]);

export const useConnection = () =>
  useEntityStore(useShallow((s) => ({ status: s.status, error: s.error })));

/**
 * All `automation.*` entities (HA surfaces every automation as one). Carries
 * `attributes.id` (the Config API id), `friendly_name`, on/off `state`, and
 * `attributes.last_triggered`. Powers the Automations list, enable/disable, and
 * "run now" without any extra fetch.
 */
export const useAutomationEntities = (): HassEntity[] =>
  useEntityStore(
    useShallow((s) =>
      Object.values(s.entities).filter((e) =>
        e.entity_id.startsWith("automation."),
      ),
    ),
  );

/** All entities grouped by area, memoized on the underlying refs. */
export function useEntitiesByArea(): AreaGroup[] {
  const entities = useEntityStore((s) => s.entities);
  const areas = useEntityStore((s) => s.areas);
  const hidden = useHidden();
  return useMemo(
    () => groupByArea(Object.values(entities), areas, undefined, hidden),
    [entities, areas, hidden],
  );
}

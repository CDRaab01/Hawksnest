import { useMemo } from "react";
import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type { AreaRegistry, HassEntity } from "../lib/ha";
import { domainOf } from "../lib/ha";
import { groupByArea, type AreaGroup } from "../lib/areas";
import type { DeviceIndex, DeviceRecord } from "./ha/registry";
import { useHidden } from "./prefsStore";

export type ConnectionStatus = "demo" | "connecting" | "connected" | "error";

const EMPTY_DEVICE_INDEX: DeviceIndex = { devices: {}, entityToDevice: {} };

interface EntityState {
  entities: Record<string, HassEntity>;
  areas: AreaRegistry;
  devices: DeviceIndex;
  status: ConnectionStatus;
  error?: string;
  /**
   * Connected Home Assistant origin (the saved creds URL), or "" in demo mode.
   * Camera `<img>` URLs are resolved against this so they reach HA even when
   * Hawksnest isn't served through HA's reverse proxy (Settings can point
   * straight at HA). Same-origin deployments store the page origin → no-op.
   */
  baseUrl: string;
  /** Replace the whole snapshot (initial load / full re-sync). */
  setSnapshot: (entities: Record<string, HassEntity>, areas: AreaRegistry) => void;
  /** Replace just the entity map (a live state push); leaves areas intact. */
  setEntities: (entities: Record<string, HassEntity>) => void;
  /** Replace just the area registry. */
  setAreas: (areas: AreaRegistry) => void;
  /** Replace the device index (resolved from the registries on connect). */
  setDevices: (devices: DeviceIndex) => void;
  /** Merge a batch of entity updates (live state changes). */
  upsertEntities: (entities: HassEntity[]) => void;
  setStatus: (status: ConnectionStatus, error?: string) => void;
  /** Set the connected HA origin used to resolve camera image URLs. */
  setBaseUrl: (baseUrl: string) => void;
}

export const useEntityStore = create<EntityState>((set) => ({
  entities: {},
  areas: {},
  devices: EMPTY_DEVICE_INDEX,
  status: "connecting",
  baseUrl: "",
  setSnapshot: (entities, areas) => set({ entities, areas }),
  setEntities: (entities) => set({ entities }),
  setAreas: (areas) => set({ areas }),
  setDevices: (devices) => set({ devices }),
  upsertEntities: (list) =>
    set((s) => {
      const entities = { ...s.entities };
      for (const e of list) entities[e.entity_id] = e;
      return { entities };
    }),
  setStatus: (status, error) => set({ status, error }),
  setBaseUrl: (baseUrl) => set({ baseUrl }),
}));

// --- selector hooks (subscribe to slices, not the whole store) ---

export const useEntity = (id: string): HassEntity | undefined =>
  useEntityStore((s) => s.entities[id]);

export const useConnection = () =>
  useEntityStore(useShallow((s) => ({ status: s.status, error: s.error })));

/** Connected HA origin for resolving camera image URLs ("" in demo mode). */
export const useHaBaseUrl = (): string => useEntityStore((s) => s.baseUrl);

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

/** All `camera.*` entities (powers the Dashboard camera wall). */
export function useCameraEntities(): HassEntity[] {
  const entities = useEntityStore((s) => s.entities);
  return useMemo(
    () =>
      Object.values(entities)
        .filter((e) => domainOf(e.entity_id) === "camera")
        .sort((a, b) => a.entity_id.localeCompare(b.entity_id)),
    [entities],
  );
}

/**
 * The home's primary alarm panel (first `alarm_control_panel.*`), or undefined
 * when HA exposes none. Powers the security bar and the nav armed-state pill.
 */
export const usePrimaryAlarm = (): HassEntity | undefined =>
  useEntityStore((s) =>
    Object.values(s.entities).find((e) =>
      e.entity_id.startsWith("alarm_control_panel."),
    ),
  );

/** Device records resolved from the HA registries (Devices hub registry view). */
export const useDeviceRecords = (): DeviceRecord[] =>
  useEntityStore(useShallow((s) => Object.values(s.devices.devices)));

/** The device that owns an entity, if the registry placed it on one. */
export const useEntityDevice = (entityId: string): DeviceRecord | undefined =>
  useEntityStore((s) => {
    const deviceId = s.devices.entityToDevice[entityId];
    return deviceId ? s.devices.devices[deviceId] : undefined;
  });

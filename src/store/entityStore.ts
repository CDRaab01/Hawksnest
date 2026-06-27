import { useMemo } from "react";
import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type { AreaRegistry, HassEntity } from "../lib/ha";
import { domainOf } from "../lib/ha";
import { groupByArea, type AreaGroup } from "../lib/areas";
import { resolveCameras, type LogicalCamera } from "../lib/cameraModel";
import { overrides } from "../config/overrides";
import { zwaveControllerOffline } from "../lib/deviceHealth";
import type { DeviceIndex, DeviceRecord } from "./ha/registry";
import { useHidden } from "./prefsStore";

export type ConnectionStatus = "demo" | "connecting" | "connected" | "error";

const EMPTY_DEVICE_INDEX: DeviceIndex = { devices: {}, entityToDevice: {} };

interface EntityState {
  entities: Record<string, HassEntity>;
  areas: AreaRegistry;
  devices: DeviceIndex;
  /** entity_id → "config"/"diagnostic" for entities the main list + History hide. */
  categories: Record<string, string>;
  /** Entity ids owned by the Z-Wave JS integration (for controller-liveness detection). */
  zwaveEntityIds: string[];
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
  /** Replace the entity-category map (resolved from the registry on connect). */
  setCategories: (categories: Record<string, string>) => void;
  /** Replace the Z-Wave entity-id list (resolved from the registry on connect). */
  setZWaveEntityIds: (ids: string[]) => void;
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
  categories: {},
  zwaveEntityIds: [],
  status: "connecting",
  baseUrl: "",
  setSnapshot: (entities, areas) => set({ entities, areas }),
  setEntities: (entities) => set({ entities }),
  setAreas: (areas) => set({ areas }),
  setDevices: (devices) => set({ devices }),
  setCategories: (categories) => set({ categories }),
  setZWaveEntityIds: (zwaveEntityIds) => set({ zwaveEntityIds }),
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

/**
 * Entities that can drive a presence trigger — every `person.*`, or all
 * `device_tracker.*` as a fallback when no `person` entities exist. Powers the
 * automation builder's "who arrives/leaves" picker.
 */
export const usePresenceEntities = (): HassEntity[] =>
  useEntityStore(
    useShallow((s) => {
      const all = Object.values(s.entities);
      const people = all.filter((e) => domainOf(e.entity_id) === "person");
      if (people.length > 0) return people;
      return all.filter((e) => domainOf(e.entity_id) === "device_tracker");
    }),
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
 * Logical cameras — ring-mqtt's per-device entities (`_live`/`_snapshot`/`_event`
 * + select/ding/motion siblings) collapsed into one camera each; plain HA/Frigate
 * cameras pass through 1:1. Powers the camera wall + the Ring-style player.
 */
export function useLogicalCameras(): LogicalCamera[] {
  const entities = useEntityStore((s) => s.entities);
  return useMemo(() => resolveCameras(entities, overrides), [entities]);
}

/**
 * The home's primary alarm panel, or undefined when HA exposes none. Prefers a panel that's actually
 * reporting over one stuck `unavailable`/`unknown`, so a Ring Alarm base station that briefly drops
 * out doesn't make the UI read "No alarm panel". Powers the security bar and the nav armed pill.
 */
export const usePrimaryAlarm = (): HassEntity | undefined =>
  useEntityStore((s) => {
    const panels = Object.values(s.entities).filter((e) =>
      e.entity_id.startsWith("alarm_control_panel."),
    );
    return (
      panels.find((e) => e.state !== "unavailable" && e.state !== "unknown") ??
      panels[0]
    );
  });

/** The hidden-category map (entity_id → "config"/"diagnostic"); reference-stable across renders. */
export const useEntityCategories = (): Record<string, string> =>
  useEntityStore((s) => s.categories);

/**
 * The hidden config/diagnostic entities belonging to the same device as `entityId` — surfaced under
 * the entity detail so they stay reachable after being filtered out of the main Devices list.
 */
export function useDeviceDiagnostics(entityId: string): HassEntity[] {
  const entities = useEntityStore((s) => s.entities);
  const devices = useEntityStore((s) => s.devices);
  const categories = useEntityStore((s) => s.categories);
  return useMemo(() => {
    const deviceId = devices.entityToDevice[entityId];
    if (!deviceId) return [];
    const record = devices.devices[deviceId];
    if (!record) return [];
    return record.entityIds
      .filter((id) => id !== entityId && id in categories)
      .map((id) => entities[id])
      .filter((e): e is HassEntity => e !== undefined);
  }, [entityId, entities, devices, categories]);
}

/** Device records resolved from the HA registries (Devices hub registry view). */
export const useDeviceRecords = (): DeviceRecord[] =>
  useEntityStore(useShallow((s) => Object.values(s.devices.devices)));

/**
 * True when we're live with HA and the Z-Wave controller looks offline (every
 * Z-Wave entity is unavailable at once). Drives the app-wide warning banner.
 * Never fires in demo mode or before the registry resolves the Z-Wave entities.
 */
export const useZWaveControllerOffline = (): boolean =>
  useEntityStore((s) => {
    if (s.status !== "connected") return false;
    const zwave = s.zwaveEntityIds
      .map((id) => s.entities[id])
      .filter((e): e is HassEntity => e !== undefined);
    return zwaveControllerOffline(zwave);
  });

/** The device that owns an entity, if the registry placed it on one. */
export const useEntityDevice = (entityId: string): DeviceRecord | undefined =>
  useEntityStore((s) => {
    const deviceId = s.devices.entityToDevice[entityId];
    return deviceId ? s.devices.devices[deviceId] : undefined;
  });

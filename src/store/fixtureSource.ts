import { entities, areaRegistry } from "../fixtures/entities";
import { useEntityStore } from "./entityStore";
import type { Source } from "./source";

/** Loads the invented fixtures into the store and flags the app as demo data. */
export function createFixtureSource(): Source {
  return {
    start() {
      const map = Object.fromEntries(entities.map((e) => [e.entity_id, e]));
      const store = useEntityStore.getState();
      store.setSnapshot(map, areaRegistry);
      store.setStatus("demo");
    },
    stop() {},
  };
}

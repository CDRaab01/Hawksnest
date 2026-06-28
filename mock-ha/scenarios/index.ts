import type { Scenario } from "../wsProtocol";
import { baseEntities } from "./entities";
import { buildRegistries, buildHistory } from "./registries";

const HA_VERSION = "2024.12.0";

function base(overrides: Partial<Scenario> = {}): Scenario {
  return {
    haVersion: HA_VERSION,
    token: null, // accept any non-empty token
    entities: baseEntities,
    registries: buildRegistries(baseEntities),
    history: buildHistory(baseEntities),
    logbook: [],
    defaultDelayMs: 600,
    ...overrides,
  };
}

/**
 * Named scenarios. Each is a factory so a `reset` always gets a fresh, isolated
 * copy (the hub deep-clones on load too, but factories keep call sites honest).
 * This is the cross-harness contract — Android tests can request the same names.
 */
export const scenarios: Record<string, () => Scenario> = {
  /** Everything healthy; locks confirm after the default delay. */
  default: () => base(),

  /** Front-door lock jams: a `lock.lock` echoes `jammed`, never reaching `locked`. */
  "lock-jam": () =>
    base({
      outcomes: { "lock.lock": { outcome: "jammed", delayMs: 600 } },
    }),

  /** Auth always fails — drives the "Invalid access token." path. */
  "bad-token": () => base({ rejectAuth: true }),
};

export function getScenario(name: string): Scenario {
  const factory = scenarios[name];
  if (!factory) throw new Error(`Unknown scenario: ${name}`);
  return factory();
}

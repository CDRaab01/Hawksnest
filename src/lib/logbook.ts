import { domainOf } from "./ha";

/** One normalized History/logbook event. */
export interface LogEvent {
  /** Epoch milliseconds. */
  when: number;
  /** Human subject, e.g. "Front Door" (HA's `name`). */
  name: string;
  /** Human message, e.g. "was opened" / "turned on". */
  message: string;
  entityId: string | null;
  domain: string | null;
  state: string | null;
}

/** The loose shape HA's `logbook/get_events` returns (fields vary per entry). */
export interface RawLogbookEntry {
  when?: number | string;
  name?: string;
  message?: string;
  entity_id?: string;
  state?: string;
  domain?: string;
  context_domain?: string;
}

/** HA sends `when` as epoch *seconds* (float). Normalize to ms. */
function whenMs(when: number | string | undefined): number {
  if (typeof when === "number") return Math.round(when * 1000);
  if (typeof when === "string") {
    const t = new Date(when).getTime();
    if (Number.isFinite(t)) return t;
  }
  return 0;
}

/** Best-effort human message when HA omits one. */
function messageFor(e: RawLogbookEntry): string {
  if (e.message) return e.message;
  if (e.state) return `changed to ${e.state}`;
  return "changed";
}

/**
 * Normalize HA's logbook payload into typed, newest-first events. Entries with
 * no usable timestamp are dropped. The live source and the fixture source both
 * return this shape so the History screen is source-agnostic.
 */
export function normalizeLogbook(raw: RawLogbookEntry[]): LogEvent[] {
  return raw
    .map((e): LogEvent => {
      const entityId = e.entity_id ?? null;
      const domain = entityId
        ? domainOf(entityId)
        : (e.domain ?? e.context_domain ?? null);
      return {
        when: whenMs(e.when),
        name: e.name ?? entityId ?? "Unknown",
        message: messageFor(e),
        entityId,
        domain,
        state: e.state ?? null,
      };
    })
    .filter((e) => e.when > 0)
    .sort((a, b) => b.when - a.when);
}

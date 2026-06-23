import { PanelCard } from "../components/PanelCard";
import { resolveName, resolveIcon } from "../lib/resolve";
import type { CardProps } from "./types";

/**
 * Fallback card for any domain without a first-class card. Read-only: resolved
 * name + state, a domain-default icon. Never crashes on an unknown domain — the
 * graceful-degradation backstop.
 */
export function GenericCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const Icon = resolveIcon(entity, overrides);
  const unavailable =
    entity.state === "unavailable" || entity.state === "unknown";

  return (
    <PanelCard className="p-lg">
      <div className="flex items-center gap-md">
        <Icon className="text-ink-dim" size={24} />
        <div className="min-w-0">
          <div className="truncate font-body text-body-lg text-ink">{name}</div>
          <div
            className={[
              "font-body text-body",
              unavailable ? "text-streak" : "text-ink-dim",
            ].join(" ")}
          >
            {unavailable ? "Unavailable" : entity.state}
          </div>
        </div>
      </div>
    </PanelCard>
  );
}

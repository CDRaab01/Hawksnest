import { PanelCard } from "../components/PanelCard";
import { resolveName, resolveIcon } from "../lib/resolve";
import type { CardProps } from "./types";

/**
 * Fallback card for any domain without a first-class card. Read-only: resolved
 * name + state, a domain-default icon. Never crashes on an unknown domain — the
 * graceful-degradation backstop.
 */
export function GenericCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const Icon = resolveIcon(entity, overrides);
  const unavailable =
    entity.state === "unavailable" || entity.state === "unknown";
  const unit = entity.attributes.unit_of_measurement as string | undefined;
  const compact = density === "compact";

  return (
    <PanelCard className={compact ? "p-md" : "p-lg"}>
      <div className="flex items-center gap-md">
        <Icon className="text-ink-dim" size={compact ? 22 : 24} />
        <div className="min-w-0">
          <div
            className={[
              "truncate font-body text-ink",
              compact ? "text-body" : "text-body-lg",
            ].join(" ")}
          >
            {name}
          </div>
          <div
            className={[
              "font-body",
              compact ? "text-caption" : "text-body",
              unavailable ? "text-streak" : "text-ink-dim",
            ].join(" ")}
          >
            {unavailable
              ? "Unavailable"
              : unit
                ? `${entity.state} ${unit}`
                : entity.state}
          </div>
        </div>
      </div>
    </PanelCard>
  );
}

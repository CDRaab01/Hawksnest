import { Link, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { EntityCard } from "../components/EntityCard";
import { PanelCard } from "../components/PanelCard";
import { SectionHeader } from "../components/SectionHeader";
import { overrides } from "../config/overrides";
import { cardDensityFor, isFeature } from "../lib/density";
import { useEntitiesByArea } from "../store/entityStore";

/**
 * Area detail (drill-in). Mixed density: feature tiles (camera) span full width,
 * controls render comfortable, read-only sensors render compact.
 */
export function AreaScreen() {
  const { area = "" } = useParams();
  const decoded = decodeURIComponent(area);
  const group = useEntitiesByArea().find((g) => g.area === decoded);

  return (
    <div className="space-y-md">
      <Link
        to="/"
        className="inline-flex items-center gap-xs text-body text-ink-dim transition-colors duration-fast hover:text-ink"
      >
        <ArrowLeft size={16} /> Home
      </Link>
      <SectionHeader label={decoded} channel="recovery" />
      {group ? (
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
          {group.entities.map((entity) => (
            <div
              key={entity.entity_id}
              className={isFeature(entity.entity_id) ? "sm:col-span-2" : ""}
            >
              <EntityCard
                entity={entity}
                overrides={overrides}
                density={cardDensityFor(entity.entity_id)}
              />
            </div>
          ))}
        </div>
      ) : (
        <PanelCard className="p-lg">
          <p className="font-body text-body text-ink-dim">No devices in this area.</p>
        </PanelCard>
      )}
    </div>
  );
}

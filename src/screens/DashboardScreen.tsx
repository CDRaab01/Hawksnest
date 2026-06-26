import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { SecurityStatusBar } from "../components/SecurityStatusBar";
import { CameraWall } from "../components/CameraWall";
import { useEntitiesByArea } from "../store/entityStore";

/**
 * Dashboard — a glanceable, camera-forward landing screen (Ring-style). Security posture up top
 * (big arm circles + a one-line secure/at-risk summary), then the camera wall as the visual focus,
 * then a single compact "Rooms" entry. Device controls live one tap deeper, inside each room
 * (`/area/:area`) and the full room grid on `/rooms` — keeping this page uncluttered.
 */
export function DashboardScreen() {
  const areas = useEntitiesByArea();
  const preview = areas.map((a) => a.area).slice(0, 4).join(" · ");

  return (
    <div className="space-y-xl">
      <SecurityStatusBar />

      <CameraWall />

      {areas.length > 0 && (
        <section className="space-y-md">
          <SectionHeader label="Rooms" channel="recovery" />
          <Link to="/rooms">
            <PanelCard className="p-lg transition-transform duration-fast active:scale-[0.99]">
              <div className="flex items-center gap-md">
                <div className="min-w-0">
                  <div className="font-display text-title text-ink">
                    {areas.length} {areas.length === 1 ? "room" : "rooms"}
                  </div>
                  <div className="truncate font-body text-body text-ink-dim">{preview}</div>
                </div>
                <ChevronRight className="ml-auto shrink-0 text-ink-faint" size={20} />
              </div>
            </PanelCard>
          </Link>
        </section>
      )}
    </div>
  );
}

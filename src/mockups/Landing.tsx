import { Link } from "react-router-dom";
import { ArrowRight, AlertTriangle } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { SectionHeader } from "../components/SectionHeader";

const DIRECTIONS = [
  {
    to: "/a",
    title: "A · Spotter-faithful list",
    body: "Vertical sections, large comfortable cards, one primary action each. Closest to Spotter's feel.",
  },
  {
    to: "/b",
    title: "B · Dense grid",
    body: "Compact tiles, more visible at once — a control-panel density for desktop power users.",
  },
  {
    to: "/c",
    title: "C · Area-first hub",
    body: "Top-level cards per Area that drill into a detail view. Most app-like for many entities.",
  },
];

export function Landing() {
  return (
    <div className="space-y-xl">
      <PanelCard className="bg-hero p-xl">
        <h1 className="font-display text-display-lg font-bold text-white">
          Pick a direction
        </h1>
        <p className="mt-sm max-w-xl font-body text-body-lg text-white/85">
          Three takes on the same dashboard, all built on Spotter's ported PULSE
          tokens (dark-only). Each renders the exact Security scene from your stock
          Home Assistant screenshot so you can compare against the "before".
        </p>
      </PanelCard>

      <section className="space-y-md">
        <SectionHeader label="The “before” we’re replacing" channel="streak" />
        <PanelCard tint="streak" className="p-lg">
          <div className="flex gap-md">
            <AlertTriangle className="shrink-0 text-streak" size={22} />
            <div className="space-y-sm font-body text-body text-ink-dim">
              <p>
                Stock HA leaks raw, attribute-derived names. All three non-camera
                tiles come from the one Schlage and its Z-Wave diagnostics:
              </p>
              <ul className="space-y-xs">
                <li>
                  <span className="text-ink">“Lock” / “Locked”</span> → generic →{" "}
                  <span className="text-recovery">“Front Door”</span>
                </li>
                <li>
                  <span className="text-ink">“Lock Current status …” / “Open”</span>{" "}
                  → raw attribute →{" "}
                  <span className="text-recovery">“Front Door” · Open</span>
                </li>
                <li>
                  <span className="text-ink">“Lock Intrusion” / “Safe”</span> →{" "}
                  <span className="text-recovery">“Intrusion” · Safe</span>
                </li>
              </ul>
              <p>
                Hawksnest's label-resolution layer fixes this everywhere — see it
                live in each direction below.
              </p>
            </div>
          </div>
        </PanelCard>
      </section>

      <section className="space-y-md">
        <SectionHeader label="Directions" channel="effort" />
        <div className="grid grid-cols-1 gap-md md:grid-cols-3">
          {DIRECTIONS.map((d) => (
            <PanelCard key={d.to} className="flex flex-col p-lg">
              <div className="font-display text-title text-ink">{d.title}</div>
              <p className="mt-sm flex-1 font-body text-body text-ink-dim">
                {d.body}
              </p>
              <Link to={d.to} className="mt-lg">
                <PulseButton compact>
                  View <ArrowRight size={16} />
                </PulseButton>
              </Link>
            </PanelCard>
          ))}
        </div>
      </section>
    </div>
  );
}

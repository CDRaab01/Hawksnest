import { Link, NavLink } from "react-router-dom";
import {
  Shield,
  LayoutDashboard,
  History as HistoryIcon,
  HardDrive,
  Workflow,
  Settings as SettingsIcon,
  type LucideIcon,
} from "lucide-react";
import { ConnectionPill } from "./ConnectionPill";
import { ArmedPill } from "./ArmedPill";

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Exact match only (so "/" isn't active on every route). */
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
  { to: "/history", label: "History", icon: HistoryIcon },
  { to: "/devices", label: "Devices", icon: HardDrive },
  { to: "/automations", label: "Automations", icon: Workflow },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
];

function navClass({ isActive }: { isActive: boolean }): string {
  return [
    "inline-flex items-center gap-xs rounded-sm px-md py-sm font-body text-body transition-colors duration-fast",
    isActive
      ? "bg-panel-high text-ink"
      : "text-ink-dim hover:bg-panel hover:text-ink",
  ].join(" ");
}

/**
 * Ring-style persistent top navigation. Brand at the left, primary destinations
 * across the bar, security + connection state pinned to the right so they read
 * on every screen. Horizontally scrollable on narrow viewports.
 */
export function TopNav() {
  return (
    <header className="sticky top-0 z-20 border-b border-hairline bg-bg/90 backdrop-blur">
      <div className="mx-auto flex max-w-[1600px] items-center gap-lg px-lg py-md">
        <Link to="/" className="flex shrink-0 items-center gap-sm">
          <Shield className="text-effort" size={22} />
          <span className="font-display text-title font-bold text-ink">Hawksnest</span>
        </Link>

        <nav className="flex min-w-0 items-center gap-xs overflow-x-auto">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={navClass}>
              <item.icon size={16} className="shrink-0" />
              <span className="whitespace-nowrap">{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="ml-auto flex shrink-0 items-center gap-sm">
          <ArmedPill />
          <ConnectionPill />
        </div>
      </div>
    </header>
  );
}

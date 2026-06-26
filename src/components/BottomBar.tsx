import { NavLink } from "react-router-dom";
import {
  House,
  ToggleRight,
  LayoutGrid,
  History as HistoryIcon,
  Settings as SettingsIcon,
  type LucideIcon,
} from "lucide-react";

interface Tab {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
  center?: boolean;
}

// Order is the on-screen order — Home sits in the middle as the large circle.
const TABS: Tab[] = [
  { to: "/devices", label: "Devices", icon: ToggleRight },
  { to: "/rooms", label: "Rooms", icon: LayoutGrid },
  { to: "/", label: "Home", icon: House, end: true, center: true },
  { to: "/history", label: "History", icon: HistoryIcon },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
];

/**
 * Spotter-style bottom navigation. Flat panel with a hairline top rule and the active item in
 * effort cyan; Home is a large raised circle in the center. Fixed to the bottom so it reads on
 * every screen, with safe-area padding for notched devices.
 */
export function BottomBar() {
  return (
    <nav className="fixed inset-x-0 bottom-0 z-30 border-t border-hairline bg-bg/95 backdrop-blur pb-[env(safe-area-inset-bottom)]">
      <div className="mx-auto flex max-w-[640px] items-end justify-around px-md pt-sm">
        {TABS.map((tab) =>
          tab.center ? (
            <NavLink
              key={tab.to}
              to={tab.to}
              end={tab.end}
              aria-label={tab.label}
              className="flex flex-col items-center"
            >
              {({ isActive }) => (
                <>
                  <span
                    className={[
                      "-mt-7 flex h-16 w-16 items-center justify-center rounded-full border transition-colors",
                      isActive
                        ? "border-effort bg-effort text-bg"
                        : "border-hairline bg-panel-high text-ink-dim",
                    ].join(" ")}
                  >
                    <tab.icon size={26} />
                  </span>
                  <span
                    className={[
                      "mt-xs font-body text-caption",
                      isActive ? "text-effort" : "text-ink-dim",
                    ].join(" ")}
                  >
                    {tab.label}
                  </span>
                </>
              )}
            </NavLink>
          ) : (
            <NavLink
              key={tab.to}
              to={tab.to}
              end={tab.end}
              aria-label={tab.label}
              className="flex flex-1 flex-col items-center gap-xs py-sm"
            >
              {({ isActive }) => (
                <>
                  <tab.icon
                    size={22}
                    className={isActive ? "text-effort" : "text-ink-dim"}
                  />
                  <span
                    className={[
                      "font-body text-caption",
                      isActive ? "text-effort" : "text-ink-dim",
                    ].join(" ")}
                  >
                    {tab.label}
                  </span>
                </>
              )}
            </NavLink>
          ),
        )}
      </div>
    </nav>
  );
}

import { NavLink, Link } from "react-router-dom";
import { Settings as SettingsIcon, Shield } from "lucide-react";
import { ConnectionPill } from "./ConnectionPill";
import { ArmedPill } from "./ArmedPill";

/**
 * Slim header strip — brand on the left, security + connection state on the right so they read on
 * every screen. Primary navigation lives in the {@link BottomBar} (Spotter-style bottom nav);
 * Settings is the gear here (Automations took its former bottom-bar slot).
 */
export function TopNav() {
  return (
    <header className="sticky top-0 z-20 border-b border-hairline bg-bg/90 backdrop-blur">
      <div className="mx-auto flex max-w-[1600px] items-center gap-lg px-lg py-md">
        <Link to="/" className="flex shrink-0 items-center gap-sm">
          <Shield className="text-effort" size={22} />
          <span className="font-display text-title font-bold text-ink">Hawksnest</span>
        </Link>
        <div className="ml-auto flex shrink-0 items-center gap-sm">
          <ArmedPill />
          <ConnectionPill />
          <NavLink
            to="/settings"
            aria-label="Settings"
            className={({ isActive }) =>
              [
                "inline-flex h-9 w-9 items-center justify-center rounded-sm border border-hairline transition-colors duration-fast",
                isActive ? "text-effort" : "text-ink-dim hover:text-ink",
              ].join(" ")
            }
          >
            <SettingsIcon size={18} />
          </NavLink>
        </div>
      </div>
    </header>
  );
}

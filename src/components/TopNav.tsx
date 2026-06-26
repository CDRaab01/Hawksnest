import { Link } from "react-router-dom";
import { Shield } from "lucide-react";
import { ConnectionPill } from "./ConnectionPill";
import { ArmedPill } from "./ArmedPill";

/**
 * Slim header strip — brand on the left, security + connection state on the right so they read on
 * every screen. Primary navigation lives in the {@link BottomBar} (Spotter-style bottom nav).
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
        </div>
      </div>
    </header>
  );
}

import { useEffect } from "react";
import { Link, NavLink, Route, Routes } from "react-router-dom";
import { Shield, Settings as SettingsIcon, Workflow } from "lucide-react";
import { HomeScreen } from "./screens/HomeScreen";
import { AreaScreen } from "./screens/AreaScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { CustomizeScreen } from "./screens/CustomizeScreen";
import { EntityScreen } from "./screens/EntityScreen";
import { AutomationsScreen } from "./screens/AutomationsScreen";
import { AutomationEditScreen } from "./screens/AutomationEditScreen";
import { ConnectionPill } from "./components/ConnectionPill";
import { startConnection, stopConnection } from "./store/connection";

function Header() {
  return (
    <header className="sticky top-0 z-10 border-b border-hairline bg-bg/90 backdrop-blur">
      <div className="mx-auto flex max-w-5xl items-center gap-md px-lg py-md">
        <Link to="/" className="flex items-center gap-sm">
          <Shield className="text-effort" size={22} />
          <span className="font-display text-title font-bold text-ink">Hawksnest</span>
        </Link>
        <div className="ml-auto flex items-center gap-md">
          <ConnectionPill />
          <NavLink
            to="/automations"
            aria-label="Automations"
            className={({ isActive }) =>
              [
                "rounded-sm p-sm transition-colors duration-fast",
                isActive ? "text-ink" : "text-ink-dim hover:text-ink",
              ].join(" ")
            }
          >
            <Workflow size={20} />
          </NavLink>
          <NavLink
            to="/settings"
            aria-label="Settings"
            className={({ isActive }) =>
              [
                "rounded-sm p-sm transition-colors duration-fast",
                isActive ? "text-ink" : "text-ink-dim hover:text-ink",
              ].join(" ")
            }
          >
            <SettingsIcon size={20} />
          </NavLink>
        </div>
      </div>
    </header>
  );
}

export default function App() {
  // Start the data source once on mount (demo fixtures, or live HA if saved).
  useEffect(() => {
    startConnection();
    return () => stopConnection();
  }, []);

  return (
    <div className="min-h-full bg-bg">
      <Header />
      <main className="mx-auto max-w-5xl px-lg py-xl">
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/area/:area" element={<AreaScreen />} />
          <Route path="/entity/:id" element={<EntityScreen />} />
          <Route path="/customize" element={<CustomizeScreen />} />
          <Route path="/automations" element={<AutomationsScreen />} />
          <Route path="/automations/:id" element={<AutomationEditScreen />} />
          <Route path="/settings" element={<SettingsScreen />} />
        </Routes>
      </main>
    </div>
  );
}

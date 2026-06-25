import { useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, Workflow } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { SectionHeader } from "../components/SectionHeader";
import { useConnection } from "../store/entityStore";
import {
  loadCredentials,
  saveCredentials,
  clearCredentials,
} from "../store/credentials";
import { startConnection } from "../store/connection";
import { defaultHaUrl } from "../lib/haUrl";

const STATUS_TEXT: Record<string, string> = {
  demo: "Demo data (no Home Assistant connected)",
  connecting: "Connecting…",
  connected: "Connected",
  error: "Disconnected",
};

/**
 * Settings — connect Hawksnest to a Home Assistant instance with a long-lived
 * access token. Saving (re)starts the live source; "Disconnect" clears the
 * token and returns to demo data.
 */
export function SettingsScreen() {
  const { status, error } = useConnection();
  const saved = loadCredentials();
  const [url, setUrl] = useState(saved?.url ?? defaultHaUrl());
  const [token, setToken] = useState("");

  const canConnect = url.trim().length > 0 && token.trim().length > 0;

  function connect() {
    saveCredentials({ url: url.trim(), token: token.trim() });
    setToken("");
    startConnection();
  }

  function disconnect() {
    clearCredentials();
    startConnection();
  }

  return (
    <div className="space-y-xl">
      <section className="space-y-md">
        <SectionHeader label="Personalization" channel="strength" />
        <Link to="/customize">
          <PanelCard className="flex items-center gap-md p-lg" tint="strength">
            <div className="min-w-0">
              <div className="font-body text-body-lg text-ink">Customize Home</div>
              <div className="font-body text-body text-ink-dim">
                Pin, reorder, and hide devices.
              </div>
            </div>
            <ChevronRight className="ml-auto shrink-0 text-ink-faint" size={20} />
          </PanelCard>
        </Link>
        <Link to="/automations">
          <PanelCard className="flex items-center gap-md p-lg" tint="effort">
            <Workflow className="shrink-0 text-effort" size={22} />
            <div className="min-w-0">
              <div className="font-body text-body-lg text-ink">Automations</div>
              <div className="font-body text-body text-ink-dim">
                Link devices — lock the doors when armed, lights on with motion.
              </div>
            </div>
            <ChevronRight className="ml-auto shrink-0 text-ink-faint" size={20} />
          </PanelCard>
        </Link>
      </section>

      <section className="space-y-md">
        <SectionHeader label="Connection" channel="effort" />
        <PanelCard className="p-lg">
          <div className="font-body text-body-lg text-ink">
            {STATUS_TEXT[status] ?? status}
          </div>
          {error && (
            <div className="mt-sm font-body text-body text-streak">{error}</div>
          )}
          {saved && (
            <div className="mt-sm font-body text-caption text-ink-faint">
              Saved: {saved.url}
            </div>
          )}
        </PanelCard>
      </section>

      <section className="space-y-md">
        <SectionHeader label="Connect to Home Assistant" channel="effort" />
        <PanelCard className="space-y-md p-lg">
          <label className="block">
            <span className="caption-label">HA URL</span>
            <input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="http://homeassistant.local:8123"
              className="mt-xs w-full rounded-sm border border-hairline bg-bg px-md py-sm font-body text-body text-ink outline-none focus:border-hairline-strong"
            />
          </label>
          <label className="block">
            <span className="caption-label">Long-lived access token</span>
            <input
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder={saved ? "Stored — paste a new one to replace" : "Paste your token"}
              className="mt-xs w-full rounded-sm border border-hairline bg-bg px-md py-sm font-body text-body text-ink outline-none focus:border-hairline-strong"
            />
          </label>
          <div className="flex flex-wrap items-center gap-md">
            <PulseButton disabled={!canConnect} onClick={connect}>
              Connect
            </PulseButton>
            {saved && (
              <PulseButton variant="ghost" onClick={disconnect}>
                Disconnect
              </PulseButton>
            )}
          </div>
          <p className="font-body text-caption text-ink-faint">
            Leave the URL as this site to use the built-in proxy; or point it
            directly at Home Assistant (e.g. http://192.168.4.34:8123). Create a
            token in HA under your profile → Long-lived access tokens. It's stored
            locally on this device.
          </p>
        </PanelCard>
      </section>
    </div>
  );
}

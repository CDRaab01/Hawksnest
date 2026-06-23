import { useState } from "react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { SectionHeader } from "../components/SectionHeader";
import { useConnection } from "../store/entityStore";

const STATUS_TEXT: Record<string, string> = {
  demo: "Demo data (no Home Assistant connected)",
  connecting: "Connecting…",
  connected: "Connected",
  error: "Disconnected",
};

/**
 * Settings — Phase 2 shows the connection state and a stub "Connect to Home
 * Assistant" form. The form is the seam for Phase 1 (URL + long-lived token);
 * it does not connect yet.
 */
export function SettingsScreen() {
  const { status, error } = useConnection();
  const [url, setUrl] = useState("http://192.168.4.34:8123");
  const [token, setToken] = useState("");

  return (
    <div className="space-y-xl">
      <section className="space-y-md">
        <SectionHeader label="Connection" channel="effort" />
        <PanelCard className="p-lg">
          <div className="font-body text-body-lg text-ink">
            {STATUS_TEXT[status] ?? status}
          </div>
          {error && (
            <div className="mt-sm font-body text-body text-streak">{error}</div>
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
              className="mt-xs w-full rounded-sm border border-hairline bg-bg px-md py-sm font-body text-body text-ink outline-none focus:border-hairline-strong"
            />
          </label>
          <label className="block">
            <span className="caption-label">Long-lived access token</span>
            <input
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="Pasted once, stored locally"
              className="mt-xs w-full rounded-sm border border-hairline bg-bg px-md py-sm font-body text-body text-ink outline-none focus:border-hairline-strong"
            />
          </label>
          <div className="flex items-center gap-md">
            <PulseButton disabled>Connect</PulseButton>
            <span className="font-body text-caption text-ink-faint">
              Live connection lands in Phase 1.
            </span>
          </div>
        </PanelCard>
      </section>
    </div>
  );
}

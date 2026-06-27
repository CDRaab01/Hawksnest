import { useEffect, useState } from "react";
import { Activity, RefreshCw } from "lucide-react";
import { PanelCard } from "../PanelCard";
import { PulseButton } from "../PulseButton";
import { callService } from "../../store/connection";

type Action = "ping" | "refresh";

// Entity-targeted Z-Wave JS services (stable across HA versions). Ping confirms
// the node is reachable; refresh_value re-reads its values from the device.
const SERVICE: Record<Action, string> = {
  ping: "ping",
  refresh: "refresh_value",
};

const RESULT_CLEAR_MS = 4000;

/**
 * Z-Wave node maintenance for a single entity: **Ping** (is the node reachable?)
 * and **Refresh** (re-read its values). Both call entity-targeted `zwave_js`
 * services through the active source. Shown on the entity detail only for Z-Wave
 * entities. Heavier operations (re-interview / rebuild routes) are intentionally
 * left to zwave-js-ui.
 */
export function ZWaveMaintenance({ entityId }: { entityId: string }) {
  const [busy, setBusy] = useState<Action | null>(null);
  const [result, setResult] = useState<string | null>(null);

  useEffect(() => {
    if (!result) return;
    const id = setTimeout(() => setResult(null), RESULT_CLEAR_MS);
    return () => clearTimeout(id);
  }, [result]);

  async function run(action: Action) {
    setBusy(action);
    setResult(null);
    try {
      await callService("zwave_js", SERVICE[action], { entity_id: entityId });
      setResult(action === "ping" ? "Ping sent." : "Refresh requested.");
    } catch {
      setResult("Couldn't reach Home Assistant.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <PanelCard className="p-lg">
      <div className="grid grid-cols-2 gap-sm">
        <PulseButton
          variant="tonal"
          channel="effort"
          disabled={busy !== null}
          onClick={() => run("ping")}
        >
          <Activity size={18} /> Ping
        </PulseButton>
        <PulseButton
          variant="tonal"
          channel="effort"
          disabled={busy !== null}
          onClick={() => run("refresh")}
        >
          <RefreshCw size={18} /> Refresh
        </PulseButton>
      </div>
      {result && (
        <div className="mt-md font-body text-caption text-ink-dim">{result}</div>
      )}
    </PanelCard>
  );
}

import { useState } from "react";
import { ImageDown } from "lucide-react";

/**
 * Save the camera's current snapshot as a file — the Reolink app's "camera"
 * button, minus a proprietary gallery. Fetches the signed snapshot URL as a blob
 * (same-origin; the token rides in the query string, so a plain `download`
 * attribute would work too — the blob round-trip is what gets us a real filename
 * instead of `camera_proxy`) and hands it to the browser as a download.
 *
 * Failure is shown inline and transient — a snapshot that didn't save is
 * self-evident, not worth a modal.
 */
export function SnapshotButton({
  snapshotUrl,
  cameraName,
}: {
  snapshotUrl: string | null;
  cameraName: string;
}) {
  const [state, setState] = useState<"idle" | "saving" | "failed">("idle");
  if (!snapshotUrl) return null;

  async function save() {
    if (state === "saving") return;
    setState("saving");
    try {
      const res = await fetch(snapshotUrl!);
      if (!res.ok) throw new Error(`snapshot ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
      a.href = url;
      a.download = `${cameraName}-${stamp}.jpg`;
      a.click();
      URL.revokeObjectURL(url);
      setState("idle");
    } catch {
      setState("failed");
      setTimeout(() => setState("idle"), 2500);
    }
  }

  return (
    <button
      type="button"
      onClick={() => void save()}
      disabled={state === "saving"}
      aria-label="Save a snapshot of this camera"
      className={[
        "flex items-center gap-xs rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
        state === "failed"
          ? "bg-panel text-streak"
          : "bg-panel text-ink-dim hover:text-ink",
      ].join(" ")}
    >
      <ImageDown size={14} />
      {state === "failed" ? "Failed" : state === "saving" ? "Saving…" : "Snapshot"}
    </button>
  );
}

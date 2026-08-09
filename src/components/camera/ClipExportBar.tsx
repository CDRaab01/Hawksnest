import { Download, X } from "lucide-react";
import { clockTime } from "../../lib/relativeTime";
import {
  NUDGE_COARSE_MS,
  NUDGE_FINE_MS,
  coverage,
  selectionDurationMs,
  selectionProblem,
  type ClipEdge,
  type ClipProblem,
  type ClipSelection,
} from "../../lib/clipExport";
import type { FootageSpan } from "../../lib/ringFootage";

/** How the download is going. `started` means the browser has taken the transfer over. */
export type ClipExportState = "idle" | "preparing" | "started" | "failed";

/**
 * The sentences for each blocking problem.
 *
 * The pure module returns an enum and never English — same split as `ScrubbedPlaceholder`'s
 * states — so the two platforms can word things idiomatically without the *rules* forking.
 */
const PROBLEM_COPY: Record<ClipProblem, string> = {
  "too-short": "Clips must be at least 5 seconds.",
  "too-long": "Clips can be at most 10 minutes.",
  "no-footage": "No recording exists for this range.",
};

function durationLabel(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

/**
 * The clip-export control bar — replaces the transport while a range is being marked.
 *
 * The interaction hierarchy is deliberate. At the timeline's opening 1-hour zoom one pixel is
 * about ten seconds, so **dragging cannot place an edge accurately** and "Start here"/"End here"
 * are the primary instrument: scrub until the video shows the moment, then mark it. The nudges are
 * the fine adjustment (1s is ffmpeg's own granularity), and the handles on the timeline are for
 * gross positioning. The readout is always the source of truth, so nobody has to read pixels.
 */
export function ClipExportBar({
  selection,
  playheadMs,
  footage,
  state,
  error,
  onNudge,
  onSetEdgeToPlayhead,
  onCancel,
  onDownload,
}: {
  selection: ClipSelection;
  /** Where the scrubber is now — what "Start here"/"End here" mark. */
  playheadMs: number;
  /** The continuous lane, used to warn before Frigate would have to refuse. */
  footage: FootageSpan[];
  state: ClipExportState;
  /** A message from the server or the signing step; outranks the coverage warning. */
  error?: string | null;
  onNudge: (edge: ClipEdge, deltaMs: number) => void;
  onSetEdgeToPlayhead: (edge: ClipEdge) => void;
  onCancel: () => void;
  onDownload: () => void;
}) {
  const problem = selectionProblem(selection, footage);
  const cover = coverage(selection, footage);
  const busy = state === "preparing";
  const chip =
    "rounded-sm bg-panel px-sm py-xs caption-label text-ink-dim transition-colors duration-fast hover:text-ink disabled:opacity-40";

  const status = error
    ? error
    : problem
      ? PROBLEM_COPY[problem]
      : cover === "partial"
        ? "Part of this range wasn't recorded — the clip will be shorter."
        : state === "started"
          ? "Download started."
          : null;

  return (
    <div className="space-y-sm rounded-md bg-panel-high p-sm">
      {(["start", "end"] as const).map((edge) => (
        <div key={edge} className="flex flex-wrap items-center gap-xs">
          <span className="w-10 shrink-0 caption-label text-ink-faint">
            {edge === "start" ? "Start" : "End"}
          </span>
          <span className="w-20 shrink-0 font-mono text-caption text-ink">
            {clockTime(edge === "start" ? selection.startMs : selection.endMs)}
          </span>
          <button type="button" className={chip} onClick={() => onNudge(edge, -NUDGE_COARSE_MS)}>
            −15s
          </button>
          <button type="button" className={chip} onClick={() => onNudge(edge, -NUDGE_FINE_MS)}>
            −1s
          </button>
          <button type="button" className={chip} onClick={() => onNudge(edge, NUDGE_FINE_MS)}>
            +1s
          </button>
          <button type="button" className={chip} onClick={() => onNudge(edge, NUDGE_COARSE_MS)}>
            +15s
          </button>
          <button
            type="button"
            className={chip}
            onClick={() => onSetEdgeToPlayhead(edge)}
            aria-label={edge === "start" ? "Set clip start to playhead" : "Set clip end to playhead"}
          >
            {edge === "start" ? "Start here" : "End here"}
          </button>
        </div>
      ))}

      <div className="flex flex-wrap items-center gap-sm">
        <span className="font-mono text-caption text-ink">
          {durationLabel(selectionDurationMs(selection))}
        </span>
        {/* What "here" means. Without it the two mark buttons are aiming at an unnamed moment —
            the video shows it, but not as a time you can compare against the two readouts above. */}
        <span className="caption-label text-ink-faint">
          Playhead {clockTime(playheadMs)}
        </span>
        <button
          type="button"
          onClick={onCancel}
          className="ml-auto flex items-center gap-xs rounded-sm bg-panel px-md py-xs caption-label text-ink-dim transition-colors duration-fast hover:text-ink"
        >
          <X size={14} />
          Cancel
        </button>
        <button
          type="button"
          onClick={onDownload}
          disabled={problem !== null || busy}
          className="flex items-center gap-xs rounded-sm bg-recovery px-md py-xs caption-label text-black transition-opacity duration-fast disabled:opacity-40"
        >
          <Download size={14} />
          {busy ? "Preparing…" : "Download"}
        </button>
      </div>

      {status && (
        <p
          className={[
            "font-body text-caption",
            error || problem ? "text-streak" : "text-ink-faint",
          ].join(" ")}
        >
          {status}
        </p>
      )}
    </div>
  );
}

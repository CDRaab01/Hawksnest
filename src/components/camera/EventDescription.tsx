import { useState } from "react";
import { Sparkles } from "lucide-react";
import type { CameraEvent } from "../../lib/cameraEvents";

/**
 * Frigate's AI description of the event under the playhead — the in-app answer to
 * "the push said *a person at the Kitchen*, but what were they doing?"
 *
 * The notification deliberately carries only a short Ring-style line: the
 * description is multi-paragraph, and it doesn't exist yet when the alert fires
 * (Frigate generates it after the event ends). So this is where you read it, and
 * it needs no new interaction to reach — tapping a timeline chip already seeks,
 * and the transport bar's prev/next already steps between events, so the strip
 * simply follows the playhead.
 *
 * Two states worth a row:
 *  - a description exists         → show it, clamped, tap to expand
 *  - a PERSON event without one   → it hasn't been generated yet; say that
 *
 * A dog/cat (or any non-person) event with no description renders **nothing**.
 * Frigate only describes people, so "descriptions are generated for people only"
 * is a fact about the system, not about this event — and spending a permanent row
 * on it costs real height in a viewport-bounded lightbox for no information.
 */
export function EventDescription({ event }: { event: CameraEvent | null }) {
    const [expanded, setExpanded] = useState(false);

  // Nothing under the playhead (live, or scrubbed to a gap) — render nothing
  // rather than an empty box that makes the player jump.
  if (!event) return null;

  const isPerson = event.label === "person";
  // Nothing useful to say about this one.
  if (!event.description && !isPerson) return null;
  const time = new Date(event.startMs).toLocaleTimeString([], {
    hour: "numeric",
    minute: "2-digit",
  });

  const body = event.description ?? "Description not ready yet.";
  const muted = !event.description;

  return (
    <div className="rounded-lg bg-panel px-md py-sm">
      <div className="flex items-center gap-xs caption-label text-ink-dim">
        <Sparkles size={12} aria-hidden="true" />
        <span className="capitalize">{event.label}</span>
        <span aria-hidden="true">·</span>
        <span>{time}</span>
      </div>
      <p
        // Only the real description is worth expanding; the two placeholder
        // lines are already one line.
        onClick={event.description ? () => setExpanded((e) => !e) : undefined}
        className={[
          "mt-xs font-body text-body",
          muted ? "text-ink-faint" : "text-ink-dim",
          event.description ? "cursor-pointer" : "",
          expanded ? "" : "line-clamp-2",
        ].join(" ")}
      >
        {body}
      </p>
    </div>
  );
}

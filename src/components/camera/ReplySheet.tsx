import { useEffect, useState } from "react";
import { Loader2, MessageSquare } from "lucide-react";
import { QUICK_REPLIES, replyUrl, type QuickReply } from "../../lib/quickReply";

/**
 * Prerecorded messages, played out of the camera's own speaker.
 *
 * The audio never touches the browser. go2rtc reads the file from its config volume and pushes it
 * into the camera's audio backchannel — so this is one HTTP call, with no microphone permission,
 * no peer connection and no 2–4s negotiation. See `quickReplyPath`.
 *
 * **The result is always shown.** A reply that fails quietly is worse than no button at all,
 * because the user walks away believing they said something to whoever is at the door. Failure
 * stays on screen until dismissed rather than reverting to idle.
 *
 * Twin of `ui/cameras/ReplySheet.kt` — same three replies, same states, same wording.
 */

/** Where a tapped reply has got to. Failure is a visible, terminal state — never a silent no-op. */
type ReplyState =
  | { kind: "idle" }
  | { kind: "sending"; id: string }
  | { kind: "sent"; id: string }
  | { kind: "failed"; id: string };

/** Mirrors the Android client's short timeouts: a wedged call must not hang the sheet open. */
const REPLY_TIMEOUT_MS = 15_000;

async function sendReply(cameraName: string, reply: QuickReply): Promise<boolean> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), REPLY_TIMEOUT_MS);
  try {
    // go2rtc wants a POST; the body is irrelevant, the query carries everything.
    const res = await fetch(replyUrl(cameraName, reply), { method: "POST", signal: ctl.signal });
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

export function ReplySheet({
  cameraName,
  displayName,
  onClose,
}: {
  cameraName: string;
  displayName: string;
  onClose: () => void;
}) {
  const [state, setState] = useState<ReplyState>({ kind: "idle" });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  function send(reply: QuickReply) {
    // Ignore taps while one is in flight: two overlapping pushes to the same speaker would
    // talk over each other.
    if (state.kind === "sending") return;
    setState({ kind: "sending", id: reply.id });
    void sendReply(cameraName, reply).then((ok) =>
      setState({ kind: ok ? "sent" : "failed", id: reply.id }),
    );
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Quick reply"
      onClick={onClose}
      className="fixed inset-0 z-50 overflow-y-auto bg-black/85 p-lg backdrop-blur"
    >
      <div className="flex min-h-full items-center justify-center">
        <div
          onClick={(e) => e.stopPropagation()}
          data-testid="replySheet"
          className="w-full max-w-md rounded-md bg-surface p-lg"
        >
          <h2 className="section-title text-ink">Quick reply</h2>
          <p className="mb-md caption-label text-ink-dim">Plays out loud on {displayName}.</p>

          <div className="flex flex-col gap-xs">
            {QUICK_REPLIES.map((reply) => {
              const sending = state.kind === "sending" && state.id === reply.id;
              const sent = state.kind === "sent" && state.id === reply.id;
              const failed = state.kind === "failed" && state.id === reply.id;
              return (
                <button
                  key={reply.id}
                  type="button"
                  onClick={() => send(reply)}
                  disabled={state.kind === "sending"}
                  data-testid={`reply_${reply.id}`}
                  className={[
                    "flex items-center gap-sm rounded-sm px-md py-sm text-left transition-colors duration-fast",
                    sent
                      ? "bg-recovery-dim text-recovery"
                      : failed
                        ? "bg-streak-dim text-streak"
                        : "bg-panel text-ink hover:text-ink",
                  ].join(" ")}
                >
                  <span className="flex-1">{reply.label}</span>
                  {sending && <Loader2 size={15} className="animate-spin motion-reduce:animate-none" />}
                  {sent && <span className="caption-label">played</span>}
                  {failed && <span className="caption-label">failed</span>}
                </button>
              );
            })}
          </div>

          {state.kind === "failed" && (
            <p className="mt-sm caption-label text-streak">
              Couldn&apos;t play that. The camera may be unreachable, or the message file is
              missing on the server.
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

/** Opens the sheet. Rendered beside Talk, under the same speaker gate. */
export function ReplyButton({ onOpen }: { onOpen: () => void }) {
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label="Play a quick reply through the camera"
      className="flex items-center gap-xs rounded-sm bg-panel px-sm py-xs caption-label text-ink-dim transition-colors duration-fast hover:text-ink"
    >
      <MessageSquare size={14} />
      Reply
    </button>
  );
}

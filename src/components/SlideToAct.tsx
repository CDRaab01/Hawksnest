import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from "react";
import { Loader } from "lucide-react";
import type { Channel } from "./PanelCard";

/** Fraction of the track the thumb must cross for release to commit. */
const COMMIT_FRACTION = 0.8;

const THUMB_PX = 44;
const PAD_PX = 5;

const CHANNEL_BG: Record<Channel, string> = {
  effort: "bg-effort",
  recovery: "bg-recovery",
  strength: "bg-strength",
  streak: "bg-streak",
};
const CHANNEL_DIM: Record<Channel, string> = {
  effort: "bg-effort-dim",
  recovery: "bg-recovery-dim",
  strength: "bg-strength-dim",
  streak: "bg-streak-dim",
};
const CHANNEL_ON: Record<Channel, string> = {
  effort: "text-effort-on",
  recovery: "text-recovery-on",
  strength: "text-strength-on",
  streak: "text-streak-on",
};

interface SlideToActProps {
  /** Hint label shown on the track ("Slide to unlock"). */
  label: string;
  /** Label while pending ("Unlocking…"). */
  pendingLabel: string;
  /** Icon inside the thumb (the action's glyph). */
  icon: ReactNode;
  /** PULSE channel of the ACTION (streak for unlock, recovery for lock). */
  channel: Channel;
  /** Honest pending: thumb holds at the end with a spinner until HA echoes. */
  pending: boolean;
  disabled?: boolean;
  onCommit: () => void;
  /** Test hook mirrors the Android component (`slide-<entityId>`). */
  testId?: string;
}

/**
 * Slide-to-act — the web port of Android's `SlideToAct`, for actions that must
 * be deliberate (unlocking the front door from the wall tablet). The drag *is*
 * the confirmation: a tap does nothing, releasing past the commit point fires
 * [onCommit], and while [pending] the thumb holds at the far end with a spinner
 * until HA's echo settles the state (the lock non-optimism invariant, rendered
 * as a feature).
 *
 * Keyboard users commit with Enter/Space on the focused thumb — a keyboard has
 * no pocket-tap failure mode, so the drag ceremony isn't imposed on it. On
 * devices with a vibrator (the Android wall tablet), commit gives a short tick.
 */
export function SlideToAct({
  label,
  pendingLabel,
  icon,
  channel,
  pending,
  disabled = false,
  onCommit,
  testId,
}: SlideToActProps) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [offset, setOffset] = useState(0);
  const [dragging, setDragging] = useState(false);
  // Set while the pointer is past the commit point (arms the release).
  const armed = useRef(false);
  const dragStartX = useRef(0);

  const maxOffset = useCallback(() => {
    const w = trackRef.current?.clientWidth ?? 0;
    return Math.max(0, w - THUMB_PX - PAD_PX * 2);
  }, []);

  // Pending holds the thumb at the end; settling (pending → false) springs home.
  useEffect(() => {
    if (pending) setOffset(maxOffset());
    else if (!dragging) setOffset(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- dragging resets are pointer-owned
  }, [pending, maxOffset]);

  function commit() {
    if (disabled || pending) return;
    navigator.vibrate?.(20);
    setOffset(maxOffset());
    onCommit();
  }

  function onPointerDown(e: PointerEvent<HTMLDivElement>) {
    if (disabled || pending) return;
    dragStartX.current = e.clientX - offset;
    setDragging(true);
    armed.current = false;
    e.currentTarget.setPointerCapture(e.pointerId);
  }

  function onPointerMove(e: PointerEvent<HTMLDivElement>) {
    if (!dragging) return;
    const next = Math.min(maxOffset(), Math.max(0, e.clientX - dragStartX.current));
    const nowArmed = maxOffset() > 0 && next >= maxOffset() * COMMIT_FRACTION;
    if (nowArmed && !armed.current) navigator.vibrate?.(10);
    armed.current = nowArmed;
    setOffset(next);
  }

  function onPointerUp() {
    if (!dragging) return;
    setDragging(false);
    if (armed.current) commit();
    else setOffset(0);
    armed.current = false;
  }

  function onKeyDown(e: KeyboardEvent<HTMLButtonElement>) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      commit();
    }
  }

  const progress = maxOffset() > 0 ? offset / maxOffset() : 0;

  return (
    <div
      ref={trackRef}
      data-testid={testId}
      data-pending={pending || undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      className={[
        "relative h-[54px] touch-none select-none overflow-hidden rounded-[27px]",
        CHANNEL_DIM[channel],
        disabled ? "opacity-45" : "",
      ].join(" ")}
    >
      {/* Fill trailing the thumb — the track charges with the channel as you slide. */}
      <div
        aria-hidden="true"
        className={["absolute inset-y-0 left-0 rounded-[27px] opacity-25", CHANNEL_BG[channel]].join(" ")}
        style={{ width: offset + THUMB_PX + PAD_PX * 2 }}
      />
      {/* Hint label, fading as the slide progresses. Centered in the space
          RIGHT of the resting thumb — centering across the whole track put the
          first characters under the thumb on narrow cards ("e to unlock"). */}
      <span
        className="pointer-events-none absolute inset-y-0 right-0 flex items-center justify-center truncate font-body text-body text-ink-dim"
        style={{
          left: THUMB_PX + PAD_PX * 2,
          paddingRight: PAD_PX * 2,
          opacity: pending ? 1 : Math.max(0, 1 - progress * 1.4),
        }}
      >
        {pending ? pendingLabel : label}
      </span>
      {/* The thumb: channel disc with the action icon, spinner while pending. */}
      <button
        type="button"
        aria-label={label}
        disabled={disabled || pending}
        onKeyDown={onKeyDown}
        className={[
          "absolute top-[5px] flex h-[44px] w-[44px] items-center justify-center rounded-full",
          CHANNEL_BG[channel],
          CHANNEL_ON[channel],
          dragging ? "" : "transition-[left] duration-emphasized ease-decel motion-reduce:transition-none",
          "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current",
        ].join(" ")}
        style={{ left: PAD_PX + offset }}
      >
        {pending ? <Loader className="animate-spin" size={20} /> : icon}
      </button>
    </div>
  );
}

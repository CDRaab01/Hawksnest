import type { KeyboardEvent, MouseEvent, ReactNode } from "react";
import { useNavigate } from "react-router-dom";

/** Interactive controls inside a card act in place; everything else navigates. */
const INTERACTIVE = 'button, input, a, label, [role="switch"], [role="slider"]';

interface CardLinkProps {
  to: string;
  children: ReactNode;
  className?: string;
}

/**
 * PULSE action grammar: a card body navigates, but its buttons/sliders act.
 * We use a role="link" wrapper (not <Link>) because cards contain real
 * <button>/<input> controls, which are invalid nested inside an <a>. A click
 * (or Enter/Space) navigates unless it originated on an interactive control.
 */
export function CardLink({ to, children, className = "" }: CardLinkProps) {
  const navigate = useNavigate();

  function fromControl(target: EventTarget | null): boolean {
    return target instanceof Element && target.closest(INTERACTIVE) !== null;
  }

  function onClick(e: MouseEvent) {
    if (fromControl(e.target)) return;
    navigate(to);
  }

  function onKeyDown(e: KeyboardEvent) {
    if (fromControl(e.target)) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      navigate(to);
    }
  }

  return (
    <div
      role="link"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={onKeyDown}
      className={["cursor-pointer", className].join(" ")}
    >
      {children}
    </div>
  );
}

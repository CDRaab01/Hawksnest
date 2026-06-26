import { useEffect, useRef, useState } from "react";
import { ChevronDown } from "lucide-react";
import type { LogicalCamera } from "../../lib/cameraModel";

/**
 * In-player camera dropdown (Ring's "Front ▾"). Switches the active camera
 * without leaving the player. Closes on outside-click or Escape.
 */
export function CameraSwitcher({
  cameras,
  current,
  onSelect,
}: {
  cameras: LogicalCamera[];
  current: LogicalCamera;
  onSelect: (camera: LogicalCamera) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={cameras.length < 2}
        className="flex items-center gap-xs rounded-md bg-panel-high px-md py-sm font-display text-headline text-ink disabled:opacity-60"
      >
        {current.name}
        {cameras.length > 1 && <ChevronDown size={18} className="text-ink-dim" />}
      </button>

      {open && (
        <ul
          role="listbox"
          className="absolute left-0 top-full z-10 mt-xs min-w-[12rem] overflow-hidden rounded-md border border-hairline bg-panel shadow-lg"
        >
          {cameras.map((cam) => {
            const selected = cam.id === current.id;
            return (
              <li key={cam.id} role="option" aria-selected={selected}>
                <button
                  type="button"
                  onClick={() => {
                    onSelect(cam);
                    setOpen(false);
                  }}
                  className={[
                    "block w-full px-md py-sm text-left font-body text-body transition-colors duration-fast",
                    selected ? "bg-panel-high text-ink" : "text-ink-dim hover:bg-panel-high hover:text-ink",
                  ].join(" ")}
                >
                  {cam.name}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

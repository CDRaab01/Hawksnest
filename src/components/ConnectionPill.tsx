import { useConnection, type ConnectionStatus } from "../store/entityStore";

const META: Record<
  ConnectionStatus,
  { label: string; dot: string; text: string }
> = {
  demo: { label: "Demo data", dot: "bg-ink-faint", text: "text-ink-dim" },
  connecting: { label: "Connecting", dot: "bg-effort", text: "text-effort" },
  connected: { label: "Connected", dot: "bg-recovery", text: "text-recovery" },
  error: { label: "Offline", dot: "bg-streak", text: "text-streak" },
};

/** Small connection-status indicator shown in the header. */
export function ConnectionPill() {
  const { status } = useConnection();
  const meta = META[status];
  return (
    <span className="inline-flex items-center gap-xs rounded-sm border border-hairline px-sm py-xs">
      <span className={["h-2 w-2 rounded-full", meta.dot].join(" ")} />
      <span className={["font-body text-caption", meta.text].join(" ")}>
        {meta.label}
      </span>
    </span>
  );
}

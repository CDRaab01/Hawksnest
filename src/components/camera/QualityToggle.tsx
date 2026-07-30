/**
 * Live-quality selector — the Reolink app's "Low/High" pill. High is the
 * camera's main stream (2.5K+, ~3-5 Mbps, fixed bitrate); Low is its sub stream
 * (640x360-ish, ~256 kbps), served by go2rtc under `<camera>_sub`. The manual
 * step-down is the practical answer to weak links: the main stream has no
 * bitrate adaptation, so on bad cellular it stalls rather than degrades — Low
 * keeps a live picture flowing at a fortieth of the bandwidth.
 *
 * Rendered only when go2rtc actually lists the `_sub` stream (Ring cameras have
 * none), which the parent checks off the fetched stream list.
 */
export function QualityToggle({
  quality,
  onChange,
}: {
  quality: "high" | "low";
  onChange: (quality: "high" | "low") => void;
}) {
  return (
    <div
      role="group"
      aria-label="Live stream quality"
      className="flex items-center rounded-sm bg-panel p-[2px]"
    >
      {(["low", "high"] as const).map((q) => (
        <button
          key={q}
          type="button"
          onClick={() => onChange(q)}
          aria-pressed={quality === q}
          className={[
            "whitespace-nowrap rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
            quality === q ? "bg-panel-high text-ink" : "text-ink-dim hover:text-ink",
          ].join(" ")}
        >
          {q === "low" ? "Low" : "High"}
        </button>
      ))}
    </div>
  );
}

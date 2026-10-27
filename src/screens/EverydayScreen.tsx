import { useCallback, useEffect, useState } from "react";
import { Check, Download, Film, RefreshCw, Sparkles } from "lucide-react";
import { SectionHeader } from "../components/SectionHeader";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { Skeleton } from "../components/Skeleton";
import { loadCredentials } from "../store/credentials";
import {
  cameraLabel,
  dayLabel,
  fetchDay,
  fetchDays,
  fetchMediaUrl,
  fetchReels,
  localDate,
  requestScan,
  setPick,
  streak,
  timeLabel,
  type EverydayCandidate,
  type EverydayDay,
  type EverydayReel,
  type EverydaySummary,
} from "../lib/everyday";

/** Authenticated media as a blob URL, revoked when `rel` changes or the component unmounts. */
function useMedia(token: string, rel: string | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!rel) {
      setUrl(null);
      return;
    }
    const ac = new AbortController();
    let made: string | null = null;
    fetchMediaUrl(token, rel, ac.signal)
      .then((u) => {
        made = u;
        setUrl(u);
      })
      .catch(() => setUrl(null));
    return () => {
      ac.abort();
      if (made) URL.revokeObjectURL(made);
    };
  }, [token, rel]);
  return url;
}

function Thumb({ token, rel, alt }: { token: string; rel: string | null | undefined; alt: string }) {
  const src = useMedia(token, rel);
  return src ? (
    <img src={src} alt={alt} className="aspect-video w-full rounded-sm object-cover" />
  ) : (
    <Skeleton className="aspect-video w-full rounded-sm" />
  );
}

function ClipPlayer({ token, candidate }: { token: string; candidate: EverydayCandidate | null }) {
  const src = useMedia(token, candidate?.clip);
  if (!candidate) {
    return (
      <div className="flex aspect-video w-full items-center justify-center rounded-sm border border-hairline text-ink-dim">
        No clip for this day
      </div>
    );
  }
  return src ? (
    <video
      key={src}
      src={src}
      autoPlay
      muted
      loop
      playsInline
      className="aspect-video w-full rounded-sm bg-bg"
      data-testid="everyday-player"
    />
  ) : (
    <Skeleton className="aspect-video w-full rounded-sm" />
  );
}

function DayChip({
  token,
  day,
  active,
  onSelect,
}: {
  token: string;
  day: EverydaySummary;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      className={[
        "w-28 shrink-0 rounded-md border p-xs text-left transition-colors duration-fast",
        active ? "border-effort" : "border-hairline hover:border-hairline-strong",
      ].join(" ")}
    >
      <Thumb token={token} rel={day.pick?.thumb} alt="" />
      <span className="mt-xs block text-caption text-ink">{dayLabel(day.date)}</span>
      <span className="block text-caption text-ink-dim">
        {day.status === "provisional" ? "Provisional" : day.manual ? "Your pick" : "Auto"}
      </span>
    </button>
  );
}

export function EverydayScreen() {
  const token = loadCredentials()?.token ?? null;
  const [days, setDays] = useState<EverydaySummary[] | null>(null);
  const [reels, setReels] = useState<EverydayReel[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [day, setDay] = useState<EverydayDay | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const loadList = useCallback(async () => {
    if (!token) return;
    try {
      const list = await fetchDays(token);
      setDays(list);
      setSelected((s) => s ?? list[0]?.date ?? null);
      setReels(await fetchReels(token).catch(() => []));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
      setDays([]);
    }
  }, [token]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  useEffect(() => {
    if (!token || !selected) return;
    let live = true;
    setDay(null);
    setPreview(null);
    fetchDay(token, selected)
      .then((d) => live && setDay(d))
      .catch((e) => live && setError((e as Error).message));
    return () => {
      live = false;
    };
  }, [token, selected]);

  if (!token) {
    return (
      <PanelCard className="p-lg text-ink-dim">
        Connect to Home Assistant in Settings to see your daily clips.
      </PanelCard>
    );
  }

  const shown =
    day?.candidates.find((c) => c.eventId === (preview ?? day.pick)) ?? day?.candidates[0] ?? null;
  const isPick = !!shown && shown.eventId === day?.pick;

  async function choose(c: EverydayCandidate) {
    if (!token || !day) return;
    setBusy(true);
    try {
      setDay(await setPick(token, day.date, c.eventId));
      setPreview(null);
      await loadList();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function rescan() {
    if (!token) return;
    const date = selected ?? localDate();
    setBusy(true);
    try {
      await requestScan(token, date);
      setError("Rescan queued. Check back in a few minutes.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function downloadReel(r: EverydayReel) {
    if (!token) return;
    setDownloading(true);
    try {
      const url = await fetchMediaUrl(token, r.url);
      const a = document.createElement("a");
      a.href = url;
      a.download = `everyday-${r.year}${r.kind === "edit" ? "-edit" : ""}.mp4`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setDownloading(false);
    }
  }

  const n = days ? streak(days) : 0;

  return (
    <div className="space-y-xl">
      <div className="space-y-md">
        <SectionHeader
          label="One second a day"
          trailing={
            n > 0 ? <span className="text-caption text-ink-dim">{n}-day streak</span> : null
          }
        />
        {error ? <p className="text-body text-ink-dim">{error}</p> : null}
        {days === null ? (
          <div className="flex gap-sm">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-24 w-28 shrink-0 rounded-md" />
            ))}
          </div>
        ) : days.length === 0 ? (
          <PanelCard className="p-lg text-ink-dim">
            Nothing yet. The first pick is made tonight at 9 pm.
          </PanelCard>
        ) : (
          <div className="flex gap-sm overflow-x-auto pb-xs">
            {days.map((d) => (
              <DayChip
                key={d.date}
                token={token}
                day={d}
                active={d.date === selected}
                onSelect={() => setSelected(d.date)}
              />
            ))}
          </div>
        )}
      </div>

      {day ? (
        <PanelCard className="space-y-md p-lg" testId="everyday-day" dataState={day.status}>
          <div className="flex flex-wrap items-center gap-sm">
            <Film size={18} className="text-effort" />
            <span className="font-display text-title text-ink">{dayLabel(day.date)}</span>
            <span className="text-caption text-ink-dim">
              {day.eventCount} events · {day.status}
              {day.genai ? "" : " · heuristics only"}
            </span>
            <PulseButton variant="ghost" compact className="ml-auto" disabled={busy} onClick={rescan}>
              <RefreshCw size={14} /> Rescan
            </PulseButton>
          </div>

          <ClipPlayer token={token} candidate={shown} />

          {shown ? (
            <div className="flex flex-wrap items-center gap-sm">
              <span className="text-body text-ink">
                {cameraLabel(shown.camera)} · {timeLabel(shown.peak)}
              </span>
              {shown.aiScore != null ? (
                <span className="inline-flex items-center gap-xs text-caption text-ink-dim">
                  <Sparkles size={12} /> {shown.aiScore}/10
                </span>
              ) : null}
              {isPick ? (
                <span className="ml-auto inline-flex items-center gap-xs text-caption text-recovery">
                  <Check size={14} /> {day.manual ? "Your pick" : "Auto pick"}
                </span>
              ) : (
                <PulseButton compact className="ml-auto" disabled={busy} onClick={() => choose(shown)}>
                  Use this one
                </PulseButton>
              )}
            </div>
          ) : null}
          {shown?.description ? <p className="text-body text-ink-dim">{shown.description}</p> : null}

          <div className="grid grid-cols-2 gap-sm sm:grid-cols-5">
            {day.candidates.map((c) => (
              <button
                key={c.eventId}
                type="button"
                onClick={() => setPreview(c.eventId)}
                aria-label={`Preview ${cameraLabel(c.camera)} at ${timeLabel(c.peak)}`}
                className={[
                  "rounded-md border p-xs text-left transition-colors duration-fast",
                  c.eventId === shown?.eventId ? "border-effort" : "border-hairline",
                ].join(" ")}
              >
                <Thumb token={token} rel={c.thumb} alt="" />
                <span className="mt-xs flex items-center gap-xs text-caption text-ink">
                  {c.eventId === day.pick ? <Check size={12} className="text-recovery" /> : null}
                  {cameraLabel(c.camera)}
                </span>
                <span className="block text-caption text-ink-dim">{timeLabel(c.peak)}</span>
              </button>
            ))}
          </div>
        </PanelCard>
      ) : selected ? (
        <Skeleton className="aspect-video w-full rounded-lg" />
      ) : null}

      {reels.length ? (
        <div className="space-y-md">
          <SectionHeader label="Reels" channel="streak" />
          {reels.map((r) => (
            <PanelCard key={r.url} className="flex items-center gap-md p-lg">
              <span className="font-display text-title text-ink">{r.year}</span>
              <span className="text-body text-ink-dim">
                {r.label ? `${r.label} · ` : ""}
                {r.days} days
              </span>
              <PulseButton
                variant="tonal"
                channel="streak"
                compact
                className="ml-auto"
                disabled={downloading}
                onClick={() => downloadReel(r)}
              >
                <Download size={14} /> {downloading ? "Preparing…" : "Download"}
              </PulseButton>
            </PanelCard>
          ))}
        </div>
      ) : null}
    </div>
  );
}

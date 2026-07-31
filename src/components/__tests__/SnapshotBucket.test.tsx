import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { SnapshotBucketProvider } from "../SnapshotBucket";
import { useSnapshotBucket } from "../snapshotBucketContext";

/** Renders both buckets so a test can assert what each camera kind would fetch. */
function Probe() {
  const frigate = useSnapshotBucket(true);
  const ring = useSnapshotBucket(false);
  return (
    <>
      <span data-testid="frigate">{frigate}</span>
      <span data-testid="ring">{ring}</span>
    </>
  );
}

const read = (id: string) => Number(screen.getByTestId(id).textContent);

/** Fire a visibilitychange with `document.hidden` stubbed to `hidden`. */
function setVisibility(hidden: boolean) {
  Object.defineProperty(document, "hidden", { value: hidden, configurable: true });
  act(() => {
    document.dispatchEvent(new Event("visibilitychange"));
  });
}

describe("SnapshotBucketProvider", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setVisibility(false);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("seeds from the session, not 0, so a cold load can't reuse a cached URL", () => {
    render(
      <SnapshotBucketProvider seed={1_700_000_000}>
        <Probe />
      </SnapshotBucketProvider>,
    );
    // The bug this guards: starting at 0 made every session's first request
    // byte-identical, so the HTTP cache could serve an arbitrarily old frame.
    expect(read("frigate")).toBe(1_700_000_000);
    expect(read("ring")).toBe(1_700_000_000);
  });

  it("advances both buckets on the shared beat", () => {
    render(
      <SnapshotBucketProvider seed={100} intervalMs={10_000}>
        <Probe />
      </SnapshotBucketProvider>,
    );
    act(() => {
      vi.advanceTimersByTime(20_000);
    });
    expect(read("frigate")).toBe(102);
    expect(read("ring")).toBe(102);
  });

  it("refreshes ONLY Frigate cameras when the app is reopened", () => {
    render(
      <SnapshotBucketProvider seed={100} intervalMs={10_000}>
        <Probe />
      </SnapshotBucketProvider>,
    );
    setVisibility(true); // backgrounded
    setVisibility(false); // reopened

    // Frigate refetches immediately; Ring waits for the shared beat, because its
    // proxy is metered and battery cams only republish every 300s anyway.
    expect(read("frigate")).toBe(101);
    expect(read("ring")).toBe(100);
  });

  it("stops ticking while backgrounded", () => {
    render(
      <SnapshotBucketProvider seed={100} intervalMs={10_000}>
        <Probe />
      </SnapshotBucketProvider>,
    );
    setVisibility(true);
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(read("ring")).toBe(100); // no beats accumulated while hidden
  });

  it("keeps the two in step once reopened", () => {
    render(
      <SnapshotBucketProvider seed={100} intervalMs={10_000}>
        <Probe />
      </SnapshotBucketProvider>,
    );
    setVisibility(true);
    setVisibility(false);
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    // one beat + one open for Frigate, one beat for Ring
    expect(read("frigate")).toBe(102);
    expect(read("ring")).toBe(101);
  });
});

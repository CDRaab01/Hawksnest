import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent, waitFor } from "@testing-library/react";
import { MuteButton } from "../MuteButton";
import { QualityToggle } from "../QualityToggle";
import { SnapshotButton } from "../SnapshotButton";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("MuteButton", () => {
  it("reads muted by default and offers the unmute gesture", () => {
    const onToggle = vi.fn();
    render(<MuteButton muted onToggle={onToggle} />);
    const btn = screen.getByRole("button", { name: /unmute camera audio/i });
    expect(btn).toHaveAttribute("aria-pressed", "false");
    expect(btn).toHaveTextContent(/muted/i);
    fireEvent.click(btn);
    expect(onToggle).toHaveBeenCalledOnce();
  });

  it("shows sound-on state when unmuted", () => {
    render(<MuteButton muted={false} onToggle={() => {}} />);
    const btn = screen.getByRole("button", { name: /mute camera audio/i });
    expect(btn).toHaveAttribute("aria-pressed", "true");
    expect(btn).toHaveTextContent(/sound/i);
  });
});

describe("QualityToggle", () => {
  it("marks the active quality and reports a change", () => {
    const onChange = vi.fn();
    render(<QualityToggle quality="high" onChange={onChange} />);
    const high = screen.getByRole("button", { name: "High" });
    const low = screen.getByRole("button", { name: "Low" });
    expect(high).toHaveAttribute("aria-pressed", "true");
    expect(low).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(low);
    expect(onChange).toHaveBeenCalledWith("low");
  });
});

describe("SnapshotButton", () => {
  it("renders nothing without a snapshot URL", () => {
    const { container } = render(<SnapshotButton snapshotUrl={null} cameraName="big_room" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("downloads the snapshot as a named file", async () => {
    const blob = new Blob(["jpg"], { type: "image/jpeg" });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({ ok: true, blob: async () => blob }) as unknown as Response),
    );
    // jsdom lacks createObjectURL; the component only needs it to return a string.
    URL.createObjectURL = vi.fn(() => "blob:snapshot");
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});

    render(<SnapshotButton snapshotUrl="/api/camera_proxy/camera.big_room?token=t" cameraName="big_room" />);
    fireEvent.click(screen.getByRole("button", { name: /save a snapshot/i }));

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:snapshot");
  });

  it("surfaces a transient failure state when the fetch dies", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false, status: 502 }) as unknown as Response));
    render(<SnapshotButton snapshotUrl="/api/camera_proxy/x" cameraName="x" />);
    fireEvent.click(screen.getByRole("button"));
    await waitFor(() => expect(screen.getByRole("button")).toHaveTextContent(/failed/i));
  });
});

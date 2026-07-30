import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent } from "@testing-library/react";
import { EventDescription } from "../EventDescription";
import type { CameraEvent } from "../../../lib/cameraEvents";

afterEach(cleanup);

function event(over: Partial<CameraEvent> = {}): CameraEvent {
  return {
    id: "e1",
    camera: "kitchen",
    label: "person",
    startMs: Date.parse("2026-07-30T14:05:00Z"),
    endMs: Date.parse("2026-07-30T14:05:20Z"),
    hasClip: true,
    hasSnapshot: true,
    thumbnailUrl: null,
    snapshotUrl: null,
    description: null,
    ...over,
  };
}

describe("EventDescription", () => {
  it("renders nothing when the playhead isn't over an event", () => {
    const { container } = render(<EventDescription event={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the description, and expands it on tap", () => {
    const long = "A person enters the kitchen carrying a box and sets it on the counter.";
    render(<EventDescription event={event({ description: long })} />);
    const body = screen.getByText(long);
    // Clamped by default — these run to paragraphs.
    expect(body.className).toContain("line-clamp-2");
    fireEvent.click(body);
    expect(body.className).not.toContain("line-clamp-2");
  });

  // The two "no text" cases mean different things and must not read the same.
  it("explains that pets are never described, rather than implying it's loading", () => {
    render(<EventDescription event={event({ label: "cat", description: null })} />);
    expect(screen.getByText(/people only/i)).toBeInTheDocument();
  });

  it("says a person event's description is still pending", () => {
    render(<EventDescription event={event({ label: "person", description: null })} />);
    expect(screen.getByText(/not ready yet/i)).toBeInTheDocument();
  });

  it("labels the event with its object type", () => {
    render(<EventDescription event={event({ label: "dog" })} />);
    expect(screen.getByText("dog")).toBeInTheDocument();
  });

  // Placeholders are single lines; only a real description is worth expanding.
  it("does not offer expansion when there is no description", () => {
    render(<EventDescription event={event({ description: null })} />);
    expect(screen.getByText(/not ready yet/i).className).not.toContain("cursor-pointer");
  });
});

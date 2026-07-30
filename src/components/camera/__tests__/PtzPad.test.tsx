import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent } from "@testing-library/react";
import { PtzPad } from "../PtzPad";
import type { PtzControls } from "../../../lib/cameraPtz";

const calls: Array<[string, string, Record<string, unknown>]> = [];
vi.mock("../../../store/connection", () => ({
  callService: (domain: string, service: string, data: Record<string, unknown>) => {
    calls.push([domain, service, data]);
    return Promise.resolve();
  },
}));

const PTZ: PtzControls = {
  slug: "big_room",
  up: "button.big_room_ptz_up",
  down: "button.big_room_ptz_down",
  left: "button.big_room_ptz_left",
  right: "button.big_room_ptz_right",
  stop: "button.big_room_ptz_stop",
  zoom: null,
  focus: null,
  autofocus: null,
  preset: null,
};

const pressed = () => calls.map((c) => c[2].entity_id);

beforeEach(() => {
  calls.length = 0;
});
afterEach(cleanup);

describe("PtzPad", () => {
  it("presses the direction on pointer down and stops on release", () => {
    render(<PtzPad ptz={PTZ} />);
    const left = screen.getByRole("button", { name: /pan left/i });
    fireEvent.pointerDown(left, { pointerId: 1 });
    expect(pressed()).toEqual(["button.big_room_ptz_left"]);
    fireEvent.pointerUp(left, { pointerId: 1 });
    expect(pressed()).toEqual(["button.big_room_ptz_left", "button.big_room_ptz_stop"]);
    expect(calls[0][0]).toBe("button");
    expect(calls[0][1]).toBe("press");
  });

  // The safety property: nothing may leave the camera panning.
  it("stops the camera when unmounted mid-move", () => {
    const { unmount } = render(<PtzPad ptz={PTZ} />);
    fireEvent.pointerDown(screen.getByRole("button", { name: /pan up/i }), { pointerId: 1 });
    expect(pressed()).toEqual(["button.big_room_ptz_up"]);
    unmount();
    expect(pressed()).toEqual(["button.big_room_ptz_up", "button.big_room_ptz_stop"]);
  });

  it("stops the camera when the tab is hidden mid-move", () => {
    render(<PtzPad ptz={PTZ} />);
    fireEvent.pointerDown(screen.getByRole("button", { name: /pan right/i }), { pointerId: 1 });
    const spy = vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    fireEvent(document, new Event("visibilitychange"));
    expect(pressed()).toContain("button.big_room_ptz_stop");
    spy.mockRestore();
  });

  it("stops on pointer cancel (a gesture stolen by a scroll)", () => {
    render(<PtzPad ptz={PTZ} />);
    const down = screen.getByRole("button", { name: /pan down/i });
    fireEvent.pointerDown(down, { pointerId: 1 });
    fireEvent.pointerCancel(down, { pointerId: 1 });
    expect(pressed()).toEqual(["button.big_room_ptz_down", "button.big_room_ptz_stop"]);
  });

  // Two directions at once would stack moves the single stop can't unwind.
  it("ignores a second direction while one is already moving", () => {
    render(<PtzPad ptz={PTZ} />);
    fireEvent.pointerDown(screen.getByRole("button", { name: /pan left/i }), { pointerId: 1 });
    fireEvent.pointerDown(screen.getByRole("button", { name: /pan right/i }), { pointerId: 2 });
    expect(pressed()).toEqual(["button.big_room_ptz_left"]);
  });

  it("does not send a redundant stop when nothing is moving", () => {
    render(<PtzPad ptz={PTZ} />);
    fireEvent.pointerUp(screen.getByRole("button", { name: /pan up/i }), { pointerId: 1 });
    expect(calls).toHaveLength(0);
  });

  it("holds and releases via the keyboard", () => {
    render(<PtzPad ptz={PTZ} />);
    const up = screen.getByRole("button", { name: /pan up/i });
    fireEvent.keyDown(up, { key: "Enter" });
    fireEvent.keyUp(up, { key: "Enter" });
    expect(pressed()).toEqual(["button.big_room_ptz_up", "button.big_room_ptz_stop"]);
  });

  // The manual escape hatch always fires, even with nothing tracked as moving.
  it("always sends stop from the explicit stop button", () => {
    render(<PtzPad ptz={PTZ} />);
    fireEvent.click(screen.getByRole("button", { name: /stop camera movement/i }));
    expect(pressed()).toEqual(["button.big_room_ptz_stop"]);
  });
});

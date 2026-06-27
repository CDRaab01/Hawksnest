import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import { TalkButton } from "../TalkButton";

afterEach(cleanup);

describe("TalkButton", () => {
  it("disables and explains itself when the mic is unavailable (no secure context)", () => {
    // jsdom has no navigator.mediaDevices.getUserMedia — the insecure-context case.
    const btn = (() => {
      render(<TalkButton src="front_door" />);
      return screen.getByRole("button");
    })();
    expect(btn).toBeDisabled();
    expect(btn).toHaveTextContent(/Talk needs HTTPS/i);
    expect(btn).toHaveAttribute("aria-label", expect.stringMatching(/requires HTTPS/i));
  });

  it("offers push-to-talk when getUserMedia is present", () => {
    // Stub a secure-context mediaDevices; we only assert the idle affordance here
    // (a full WebRTC negotiation needs a real browser).
    const original = navigator.mediaDevices;
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: { getUserMedia: async () => new MediaStream() },
    });
    try {
      render(<TalkButton src="front_door" />);
      const btn = screen.getByRole("button");
      expect(btn).not.toBeDisabled();
      expect(btn).toHaveTextContent(/Hold to talk/i);
    } finally {
      Object.defineProperty(navigator, "mediaDevices", {
        configurable: true,
        value: original,
      });
    }
  });
});

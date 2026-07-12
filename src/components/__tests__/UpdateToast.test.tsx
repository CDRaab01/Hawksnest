import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// The container imports `virtual:pwa-register/react`, which only exists when the
// PWA plugin runs. Mock it via vi.hoisted so the shared spies are safe to
// reference inside the (hoisted) factory without a TDZ/scope error.
const sw = vi.hoisted(() => ({
  updateServiceWorker: vi.fn((..._args: unknown[]) => Promise.resolve()),
  setNeedRefresh: vi.fn(),
  needRefresh: false,
}));
vi.mock("virtual:pwa-register/react", () => ({
  useRegisterSW: () => ({
    needRefresh: [sw.needRefresh, sw.setNeedRefresh],
    updateServiceWorker: sw.updateServiceWorker,
  }),
}));

import { UpdateToast, UpdateToastView } from "../UpdateToast";

beforeEach(() => {
  sw.needRefresh = false;
  sw.updateServiceWorker.mockClear();
  sw.setNeedRefresh.mockClear();
});

describe("UpdateToastView", () => {
  it("Reload triggers the reload callback", async () => {
    const user = userEvent.setup();
    const onReload = vi.fn();
    render(<UpdateToastView onReload={onReload} onDismiss={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Reload" }));
    expect(onReload).toHaveBeenCalledOnce();
  });

  it("dismiss is labelled for assistive tech", () => {
    render(<UpdateToastView onReload={() => {}} onDismiss={() => {}} />);
    expect(screen.getByRole("button", { name: "Dismiss update prompt" })).toBeTruthy();
  });
});

describe("UpdateToast", () => {
  it("renders nothing until a new worker is waiting", () => {
    sw.needRefresh = false;
    render(<UpdateToast />);
    expect(screen.queryByText("Update ready")).toBeNull();
  });

  it("prompts and activates the waiting worker on Reload", async () => {
    const user = userEvent.setup();
    sw.needRefresh = true;
    render(<UpdateToast />);
    expect(screen.getByText("Update ready")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Reload" }));
    expect(sw.updateServiceWorker).toHaveBeenCalledWith(true);
  });

  it("dismiss clears the pending flag without reloading", async () => {
    const user = userEvent.setup();
    sw.needRefresh = true;
    render(<UpdateToast />);
    await user.click(screen.getByRole("button", { name: "Dismiss update prompt" }));
    expect(sw.setNeedRefresh).toHaveBeenCalledWith(false);
    expect(sw.updateServiceWorker).not.toHaveBeenCalled();
  });
});

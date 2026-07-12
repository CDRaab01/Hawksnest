import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// The container imports the SW module `virtual:pwa-register/react`, which only
// exists when the PWA plugin runs. Mock it so we can drive needRefresh/reload.
const updateServiceWorker = vi.fn((..._args: unknown[]) => Promise.resolve());
const setNeedRefresh = vi.fn();
let needRefresh = false;
vi.mock("virtual:pwa-register/react", () => ({
  useRegisterSW: () => ({
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  }),
}));

import { UpdateToast, UpdateToastView } from "../UpdateToast";

beforeEach(() => {
  needRefresh = false;
  updateServiceWorker.mockClear();
  setNeedRefresh.mockClear();
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
    needRefresh = false;
    render(<UpdateToast />);
    expect(screen.queryByText("Update ready")).toBeNull();
  });

  it("prompts and activates the waiting worker on Reload", async () => {
    const user = userEvent.setup();
    needRefresh = true;
    render(<UpdateToast />);
    expect(screen.getByText("Update ready")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Reload" }));
    expect(updateServiceWorker).toHaveBeenCalledWith(true);
  });

  it("dismiss clears the pending flag without reloading", async () => {
    const user = userEvent.setup();
    needRefresh = true;
    render(<UpdateToast />);
    await user.click(screen.getByRole("button", { name: "Dismiss update prompt" }));
    expect(setNeedRefresh).toHaveBeenCalledWith(false);
    expect(updateServiceWorker).not.toHaveBeenCalled();
  });
});

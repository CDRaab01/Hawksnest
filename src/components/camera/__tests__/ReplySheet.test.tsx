import { describe, it, expect, afterEach, vi } from "vitest";
import { render, screen, cleanup, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReplySheet } from "../ReplySheet";
import { QUICK_REPLIES } from "../../../lib/quickReply";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function open() {
  render(
    <ReplySheet cameraName="front_door_reolink" displayName="Front Door" onClose={() => {}} />,
  );
}

describe("ReplySheet", () => {
  it("offers every reply and says where it will be heard", () => {
    open();
    for (const r of QUICK_REPLIES) expect(screen.getByText(r.label)).toBeInTheDocument();
    expect(screen.getByText(/Plays out loud on Front Door/i)).toBeInTheDocument();
  });

  it("POSTs to go2rtc's backchannel and reports success", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal("fetch", fetchMock);

    open();
    await userEvent.click(screen.getByTestId(`reply_${QUICK_REPLIES[0].id}`));

    await waitFor(() => expect(screen.getByText("played")).toBeInTheDocument());

    const [url, init] = fetchMock.mock.calls[0];
    expect(init).toMatchObject({ method: "POST" });
    // dst is the camera, src is the encoded file + codec — a raw `#` here would
    // truncate the query and the camera would receive no codec directive.
    expect(url).toContain("dst=front_door_reolink");
    expect(url).toContain("%23audio%3Dpcmu");
    expect(url).not.toContain("#");
  });

  it("shows failure as a terminal, visible state — never a silent no-op", async () => {
    // A reply that fails quietly is worse than no button: the user walks away
    // believing they spoke to whoever is at the door.
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false }));

    open();
    await userEvent.click(screen.getByTestId(`reply_${QUICK_REPLIES[1].id}`));

    await waitFor(() => expect(screen.getByText("failed")).toBeInTheDocument());
    expect(screen.getByText(/Couldn't play that/i)).toBeInTheDocument();
  });

  it("treats a thrown fetch as failure rather than leaving it spinning", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network")));

    open();
    await userEvent.click(screen.getByTestId(`reply_${QUICK_REPLIES[0].id}`));

    await waitFor(() => expect(screen.getByText("failed")).toBeInTheDocument());
  });

  it("ignores a second tap while one is in flight, so replies can't talk over each other", async () => {
    let release: (v: { ok: boolean }) => void = () => {};
    const fetchMock = vi
      .fn()
      .mockReturnValue(new Promise<{ ok: boolean }>((res) => (release = res)));
    vi.stubGlobal("fetch", fetchMock);

    open();
    await userEvent.click(screen.getByTestId(`reply_${QUICK_REPLIES[0].id}`));
    // Second reply is disabled while the first is sending.
    expect(screen.getByTestId(`reply_${QUICK_REPLIES[1].id}`)).toBeDisabled();

    release({ ok: true });
    await waitFor(() => expect(screen.getByText("played")).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DoorbellBanner } from "../DoorbellBanner";
import { useEntityStore } from "../../store/entityStore";
import { useCameraOverlay } from "../../store/cameraOverlay";

beforeEach(() => {
  useEntityStore.setState({ entities: {}, areas: {}, status: "demo" });
  useCameraOverlay.setState({ openId: null });
});

function setRinging(on: boolean) {
  useEntityStore.setState({
    entities: {
      "camera.front_door": {
        entity_id: "camera.front_door",
        state: "streaming",
        attributes: { friendly_name: "Front Door", entity_picture: "/x.svg" },
      },
      "binary_sensor.front_door_ding": {
        entity_id: "binary_sensor.front_door_ding",
        state: on ? "on" : "off",
        attributes: {},
        last_changed: new Date().toISOString(),
      },
    },
  });
}

describe("DoorbellBanner", () => {
  it("stays hidden when nothing is ringing", () => {
    setRinging(false);
    render(<DoorbellBanner />);
    expect(screen.queryByText("Doorbell")).toBeNull();
  });

  it("shows on a ding and View opens that camera's player", async () => {
    const user = userEvent.setup();
    setRinging(true);
    render(<DoorbellBanner />);

    expect(screen.getByText("Doorbell")).toBeInTheDocument();
    expect(screen.getByText("Someone's at Front Door")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "View" }));
    expect(useCameraOverlay.getState().openId).toBe("camera.front_door");
    // Dismissed after acting.
    expect(screen.queryByText("Doorbell")).toBeNull();
  });
});

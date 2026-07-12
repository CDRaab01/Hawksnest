import { useEffect } from "react";
import { Route, Routes } from "react-router-dom";
import { DashboardScreen } from "./screens/DashboardScreen";
import { RoomsScreen } from "./screens/RoomsScreen";
import { AreaScreen } from "./screens/AreaScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { CustomizeScreen } from "./screens/CustomizeScreen";
import { EntityScreen } from "./screens/EntityScreen";
import { DevicesScreen } from "./screens/DevicesScreen";
import { HistoryScreen } from "./screens/HistoryScreen";
import { AutomationsScreen } from "./screens/AutomationsScreen";
import { AutomationEditScreen } from "./screens/AutomationEditScreen";
import { TopNav } from "./components/TopNav";
import { BottomBar } from "./components/BottomBar";
import { SnapshotBucketProvider } from "./components/SnapshotBucket";
import { DoorbellBanner } from "./components/DoorbellBanner";
import { ZWaveStatusBanner } from "./components/ZWaveStatusBanner";
import { UpdateToast } from "./components/UpdateToast";
import { CameraLightbox } from "./components/CameraLightbox";
import { useLogicalCameras } from "./store/entityStore";
import { useCameraOverlay } from "./store/cameraOverlay";
import { startConnection, stopConnection } from "./store/connection";

/** App-wide full-screen camera player, opened from the wall or a doorbell alert. */
function CameraOverlay() {
  const openId = useCameraOverlay((s) => s.openId);
  const close = useCameraOverlay((s) => s.close);
  const cameras = useLogicalCameras();
  const camera = openId ? cameras.find((c) => c.id === openId) : null;
  if (!camera) return null;
  return <CameraLightbox camera={camera} onClose={close} />;
}

export default function App() {
  // Start the data source once on mount (demo fixtures, or live HA if saved).
  useEffect(() => {
    startConnection();
    return () => stopConnection();
  }, []);

  return (
    <SnapshotBucketProvider>
      <div className="min-h-full bg-bg">
        <TopNav />
        <main className="mx-auto max-w-[1600px] px-lg py-xl pb-28">
          <Routes>
            <Route path="/" element={<DashboardScreen />} />
            <Route path="/rooms" element={<RoomsScreen />} />
            <Route path="/area/:area" element={<AreaScreen />} />
            <Route path="/entity/:id" element={<EntityScreen />} />
            <Route path="/devices" element={<DevicesScreen />} />
            <Route path="/history" element={<HistoryScreen />} />
            <Route path="/customize" element={<CustomizeScreen />} />
            <Route path="/automations" element={<AutomationsScreen />} />
            <Route path="/automations/:id" element={<AutomationEditScreen />} />
            <Route path="/settings" element={<SettingsScreen />} />
          </Routes>
        </main>
        <BottomBar />
        <DoorbellBanner />
        <ZWaveStatusBanner />
        <UpdateToast />
        <CameraOverlay />
      </div>
    </SnapshotBucketProvider>
  );
}

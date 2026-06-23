import { NavLink, Route, Routes } from "react-router-dom";
import { Shield } from "lucide-react";
import { Landing } from "./mockups/Landing";
import { OptionA } from "./mockups/OptionA";
import { OptionB } from "./mockups/OptionB";
import { OptionC, OptionCArea } from "./mockups/OptionC";

const TABS = [
  { to: "/", label: "Overview", end: true },
  { to: "/a", label: "A · List", end: false },
  { to: "/b", label: "B · Grid", end: false },
  { to: "/c", label: "C · Hub", end: false },
];

function Header() {
  return (
    <header className="sticky top-0 z-10 border-b border-hairline bg-bg/90 backdrop-blur">
      <div className="mx-auto flex max-w-5xl items-center gap-md px-lg py-md">
        <Shield className="text-effort" size={22} />
        <span className="font-display text-title font-bold text-ink">Hawksnest</span>
        <span className="caption-label ml-xs hidden sm:inline">Phase 0 · mockups</span>
        <nav className="ml-auto flex gap-xs">
          {TABS.map((tab) => (
            <NavLink
              key={tab.to}
              to={tab.to}
              end={tab.end}
              className={({ isActive }) =>
                [
                  "rounded-sm px-md py-sm text-body transition-colors duration-fast",
                  isActive
                    ? "bg-panel-high text-ink"
                    : "text-ink-dim hover:text-ink",
                ].join(" ")
              }
            >
              {tab.label}
            </NavLink>
          ))}
        </nav>
      </div>
    </header>
  );
}

export default function App() {
  return (
    <div className="min-h-full bg-bg">
      <Header />
      <main className="mx-auto max-w-5xl px-lg py-xl">
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/a" element={<OptionA />} />
          <Route path="/b" element={<OptionB />} />
          <Route path="/c" element={<OptionC />} />
          <Route path="/c/:area" element={<OptionCArea />} />
        </Routes>
      </main>
    </div>
  );
}

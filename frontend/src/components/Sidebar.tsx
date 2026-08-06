import type { ReactElement } from "react";
import type { Screen } from "../screens";
import { SCREEN_TITLES } from "../screens";
import { AlertsIcon, DashboardIcon, LogsIcon, SourcesIcon, TerminalIcon } from "./icons";

type ThemeMode = "dark" | "light";

interface SidebarProps {
  screen: Screen;
  onNavigate: (screen: Screen) => void;
  collapsed: boolean;
  mode: ThemeMode;
  onToggleMode: () => void;
}

const NAV_ITEMS: { screen: Screen; icon: (props: { size?: number }) => ReactElement }[] = [
  { screen: "dashboard", icon: DashboardIcon },
  { screen: "logs", icon: LogsIcon },
  { screen: "sources", icon: SourcesIcon },
  { screen: "alerts", icon: AlertsIcon },
];

export function Sidebar({ screen, onNavigate, collapsed, mode, onToggleMode }: SidebarProps) {
  return (
    <nav className={`sidebar${collapsed ? " sidebar-collapsed" : ""}`}>
      <div className="sidebar-brand">
        <TerminalIcon size={20} />
        {!collapsed && <span>Logic</span>}
      </div>

      <div className="sidebar-nav">
        {NAV_ITEMS.map(({ screen: itemScreen, icon: Icon }) => (
          <button
            key={itemScreen}
            type="button"
            className={`sidebar-nav-item${screen === itemScreen ? " active" : ""}`}
            onClick={() => onNavigate(itemScreen)}
          >
            <Icon />
            {!collapsed && <span>{SCREEN_TITLES[itemScreen]}</span>}
          </button>
        ))}
      </div>

      <div className="sidebar-spacer" />

      <button type="button" className="btn btn-secondary sidebar-theme-btn" onClick={onToggleMode}>
        <span aria-hidden="true">{mode === "dark" ? "☀" : "☾"}</span>
        {!collapsed && <span>{mode === "dark" ? "Light mode" : "Dark mode"}</span>}
      </button>
    </nav>
  );
}

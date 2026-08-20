import type { ReactElement } from "react";
import type { Screen } from "../screens";
import { SCREEN_TITLES } from "../screens";
import type { AxiomTheme } from "../theme";
import { THEME_LABELS } from "../theme";
import { AlertsIcon, CollapseIcon, DashboardIcon, LogicMarkIcon, LogsIcon, SourcesIcon } from "./icons";

interface SidebarProps {
  screen: Screen;
  onNavigate: (screen: Screen) => void;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  mode: AxiomTheme;
  onToggleMode: () => void;
}

const NAV_ITEMS: { screen: Screen; icon: (props: { size?: number }) => ReactElement }[] = [
  { screen: "dashboard", icon: DashboardIcon },
  { screen: "logs", icon: LogsIcon },
  { screen: "sources", icon: SourcesIcon },
  { screen: "alerts", icon: AlertsIcon },
];

export function Sidebar({ screen, onNavigate, collapsed, onToggleCollapsed, mode, onToggleMode }: SidebarProps) {
  return (
    <nav className={`sidebar${collapsed ? " sidebar-collapsed" : ""}`}>
      <div className="sidebar-brand">
        <LogicMarkIcon size={32} />
        {!collapsed && <span>Logic</span>}
      </div>

      <div className="sidebar-nav-header">
        {!collapsed && <span className="sidebar-nav-label">Navigation</span>}
        <button
          type="button"
          className="btn btn-icon btn-ghost sidebar-collapse-btn"
          onClick={onToggleCollapsed}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        >
          <CollapseIcon size={13} />
        </button>
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

      <button
        type="button"
        className="btn btn-secondary sidebar-theme-btn"
        onClick={onToggleMode}
        title={`Theme: ${THEME_LABELS[mode]} — click to cycle`}
      >
        <span className="sidebar-theme-dot" aria-hidden="true" />
        {!collapsed && <span className="sidebar-theme-label">{THEME_LABELS[mode]}</span>}
      </button>
    </nav>
  );
}

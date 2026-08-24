import { useEffect, useState } from "react";
import { Alerts } from "./components/Alerts";
import { Dashboard } from "./components/Dashboard";
import { LogStream } from "./components/LogStream";
import { Sidebar } from "./components/Sidebar";
import { SourceDialog } from "./components/SourceDialog";
import { SourceGrid } from "./components/SourceGrid";
import { useAlertRules } from "./hooks/useAlertRules";
import { useSavedSearches } from "./hooks/useSavedSearches";
import { useSources } from "./hooks/useSources";
import { ApiError, reloadLogs } from "./api/client";
import type { LogSource } from "./api/types";
import type { Screen } from "./screens";
import { SCREEN_TITLES } from "./screens";
import { TestIcon } from "./components/icons";
import { THEMES } from "./theme";
import type { MahoTheme } from "./theme";
import { createLogger } from "./utils/logger";

const logger = createLogger("App");

type DialogState = { mode: "add" } | { mode: "edit"; source: LogSource };

const THEME_STORAGE_KEY = "logic.theme-mode";
const SIDEBAR_STORAGE_KEY = "logic.sidebar-collapsed";

function App() {
  const [mode, setMode] = useState<MahoTheme>(() => {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return THEMES.includes(stored as MahoTheme) ? (stored as MahoTheme) : "tsuki";
  });
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => localStorage.getItem(SIDEBAR_STORAGE_KEY) === "true",
  );
  const [screen, setScreen] = useState<Screen>(() =>
    new URLSearchParams(window.location.search).has("savedSearch") ? "logs" : "sources",
  );
  const [logCount, setLogCount] = useState(0);
  const [dialogState, setDialogState] = useState<DialogState | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [reloadSignal, setReloadSignal] = useState(0);
  const [reloading, setReloading] = useState(false);
  const { sources, loading, error, create, update, upload, remove, check, toggleEnabled, toggleLive } = useSources();
  const {
    savedSearches,
    loading: savedSearchesLoading,
    create: createSavedSearch,
    remove: removeSavedSearch,
  } = useSavedSearches();
  const {
    alertRules,
    loading: alertRulesLoading,
    create: createAlertRule,
    update: updateAlertRule,
    remove: removeAlertRule,
    toggleMuted: toggleAlertRuleMuted,
    events: fetchAlertEvents,
  } = useAlertRules();

  useEffect(() => {
    document.documentElement.dataset.theme = mode;
    localStorage.setItem(THEME_STORAGE_KEY, mode);
  }, [mode]);

  useEffect(() => {
    localStorage.setItem(SIDEBAR_STORAGE_KEY, String(sidebarCollapsed));
  }, [sidebarCollapsed]);

  const handleDelete = async (id: number) => {
    setActionError(null);
    try {
      await remove(id);
    } catch (e) {
      logger.warn(`Failed to delete source ${id}`, e);
      setActionError(e instanceof ApiError ? e.message : "Failed to delete source");
    }
  };

  const handleTest = async (id: number) => {
    setActionError(null);
    try {
      await check(id);
    } catch (e) {
      logger.warn(`Failed to test source ${id}`, e);
      setActionError(e instanceof ApiError ? e.message : "Failed to test connection");
    }
  };

  const handleToggleEnabled = async (id: number, enabled: boolean) => {
    setActionError(null);
    try {
      await toggleEnabled(id, enabled);
    } catch (e) {
      logger.warn(`Failed to ${enabled ? "enable" : "disable"} source ${id}`, e);
      setActionError(e instanceof ApiError ? e.message : `Failed to ${enabled ? "enable" : "disable"} source`);
    }
  };

  const handleToggleLive = async (id: number, live: boolean) => {
    setActionError(null);
    try {
      await toggleLive(id, live);
    } catch (e) {
      logger.warn(`Failed to mark source ${id} as ${live ? "live" : "not live"}`, e);
      setActionError(e instanceof ApiError ? e.message : `Failed to mark source as ${live ? "live" : "not live"}`);
    }
  };

  const handleReload = async () => {
    setActionError(null);
    setReloading(true);
    try {
      await reloadLogs();
      setReloadSignal((n) => n + 1);
    } catch (e) {
      logger.warn("Failed to reload logs", e);
      setActionError(e instanceof ApiError ? e.message : "Failed to reload");
    } finally {
      setReloading(false);
    }
  };

  return (
    <div className="shell">
      <Sidebar
        screen={screen}
        onNavigate={setScreen}
        collapsed={sidebarCollapsed}
        onToggleCollapsed={() => setSidebarCollapsed((c) => !c)}
        mode={mode}
        onToggleMode={() => setMode((m) => THEMES[(THEMES.indexOf(m) + 1) % THEMES.length])}
      />

      <div className="main">
        <div className="topbar">
          <h3>{SCREEN_TITLES[screen]}</h3>
          <div className="topbar-right">
            {sources.some((s) => s.enabled && s.live) && (
              <span className="live-badge" title="At least one source is being watched live">
                <span className="live-dot" />
                LIVE
              </span>
            )}
            {screen === "sources" && (
              <span className="text-muted">{sources.length} source{sources.length === 1 ? "" : "s"}</span>
            )}
            {screen === "logs" && (
              <span className="text-muted">{logCount} result{logCount === 1 ? "" : "s"}</span>
            )}
            {(screen === "logs" || screen === "dashboard") && (
              <button
                type="button"
                className="btn btn-secondary btn-small reload-btn"
                onClick={handleReload}
                disabled={reloading}
                title={
                  sources.some((s) => s.changedFiles.length > 0)
                    ? "New data available — click to refresh non-live sources"
                    : "Refresh non-live sources now"
                }
              >
                <TestIcon size={13} />
                {reloading ? "Reloading…" : "Reload"}
                {sources.some((s) => s.changedFiles.length > 0) && <span className="update-dot reload-btn-dot" />}
              </button>
            )}
          </div>
        </div>

        <div className="content">
          {(error || actionError) && <div className="error-banner">{error ?? actionError}</div>}

          {screen === "sources" && !loading && (
            <SourceGrid
              sources={sources}
              onTest={handleTest}
              onEdit={(source) => setDialogState({ mode: "edit", source })}
              onDelete={handleDelete}
              onToggleEnabled={handleToggleEnabled}
              onToggleLive={handleToggleLive}
              onAdd={() => setDialogState({ mode: "add" })}
            />
          )}

          {screen === "logs" && (
            <LogStream
              sources={sources}
              onCountChange={setLogCount}
              reloadSignal={reloadSignal}
              onReload={handleReload}
              savedSearches={savedSearches}
              savedSearchesLoading={savedSearchesLoading}
              onCreateSavedSearch={createSavedSearch}
              onDeleteSavedSearch={removeSavedSearch}
            />
          )}

          {screen === "dashboard" && !loading && (
            <Dashboard sources={sources} reloadSignal={reloadSignal} onReload={handleReload} />
          )}

          {screen === "alerts" && (
            <Alerts
              sources={sources}
              alertRules={alertRules}
              loading={alertRulesLoading}
              onCreate={createAlertRule}
              onUpdate={updateAlertRule}
              onDelete={removeAlertRule}
              onToggleMuted={toggleAlertRuleMuted}
              onFetchEvents={fetchAlertEvents}
            />
          )}
        </div>

        <div className="status-footer">
          <span>{sources.filter((s) => s.status === "REACHABLE").length} / {sources.length} SOURCES REACHABLE</span>
        </div>
      </div>

      {dialogState && (
        <SourceDialog
          source={dialogState.mode === "edit" ? dialogState.source : undefined}
          onClose={() => setDialogState(null)}
          onSubmit={(req) =>
            dialogState.mode === "edit" ? update(dialogState.source.id, req) : create(req)
          }
          onUpload={upload}
        />
      )}
    </div>
  );
}

export default App;

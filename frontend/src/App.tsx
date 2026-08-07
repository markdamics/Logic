import { useEffect, useState } from "react";
import { ComingSoon } from "./components/ComingSoon";
import { LogStream } from "./components/LogStream";
import { Sidebar } from "./components/Sidebar";
import { SourceDialog } from "./components/SourceDialog";
import { SourceGrid } from "./components/SourceGrid";
import { useSources } from "./hooks/useSources";
import { ApiError } from "./api/client";
import type { LogSource } from "./api/types";
import type { Screen } from "./screens";
import { SCREEN_TITLES } from "./screens";
import { CollapseIcon } from "./components/icons";
import { createLogger } from "./utils/logger";

const logger = createLogger("App");

type ThemeMode = "dark" | "light";
type DialogState = { mode: "add" } | { mode: "edit"; source: LogSource };

const THEME_STORAGE_KEY = "logic.theme-mode";
const SIDEBAR_STORAGE_KEY = "logic.sidebar-collapsed";

function App() {
  const [mode, setMode] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "dark";
  });
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => localStorage.getItem(SIDEBAR_STORAGE_KEY) === "true",
  );
  const [screen, setScreen] = useState<Screen>("sources");
  const [logCount, setLogCount] = useState(0);
  const [dialogState, setDialogState] = useState<DialogState | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const { sources, loading, error, create, update, remove, check } = useSources();

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

  return (
    <div className="shell">
      <Sidebar
        screen={screen}
        onNavigate={setScreen}
        collapsed={sidebarCollapsed}
        mode={mode}
        onToggleMode={() => setMode((m) => (m === "dark" ? "light" : "dark"))}
      />

      <div className="main">
        <div className="topbar">
          <button
            type="button"
            className="btn btn-icon btn-ghost"
            onClick={setSidebarCollapsed.bind(null, (c) => !c)}
            title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          >
            <CollapseIcon />
          </button>
          <h3>{SCREEN_TITLES[screen]}</h3>
          {screen === "sources" && (
            <span className="text-muted">{sources.length} source{sources.length === 1 ? "" : "s"}</span>
          )}
          {screen === "logs" && (
            <span className="text-muted">{logCount} result{logCount === 1 ? "" : "s"}</span>
          )}
        </div>

        <div className="content">
          {(error || actionError) && <div className="error-banner">{error ?? actionError}</div>}

          {screen === "sources" && !loading && (
            <SourceGrid
              sources={sources}
              onTest={handleTest}
              onEdit={(source) => setDialogState({ mode: "edit", source })}
              onDelete={handleDelete}
              onAdd={() => setDialogState({ mode: "add" })}
            />
          )}

          {screen === "logs" && <LogStream sources={sources} onCountChange={setLogCount} />}

          {screen === "dashboard" && <ComingSoon title={SCREEN_TITLES[screen]} />}
        </div>
      </div>

      {dialogState && (
        <SourceDialog
          source={dialogState.mode === "edit" ? dialogState.source : undefined}
          onClose={() => setDialogState(null)}
          onSubmit={(req) =>
            dialogState.mode === "edit" ? update(dialogState.source.id, req) : create(req)
          }
        />
      )}
    </div>
  );
}

export default App;

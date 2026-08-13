import { useState } from "react";
import type { LogSource } from "../api/types";
import { formatRelativeTime } from "../utils/time";
import { DeleteIcon, EditIcon, PlusIcon, TestIcon } from "./icons";
import { StatusChip } from "./StatusChip";

const TYPE_LABELS: Record<LogSource["type"], string> = {
  LOCAL_FILE: "Local file",
  LOCAL_DIRECTORY: "Local directory",
  SFTP: "SFTP",
  HTTP: "HTTP(S)",
};

function locationOf(source: LogSource): string {
  if (source.type === "SFTP") {
    return `${source.username ?? ""}@${source.host ?? ""}:${source.port ?? 22}${source.path ?? ""}`;
  }
  return source.path ?? "";
}

interface SourceGridProps {
  sources: LogSource[];
  onTest: (id: number) => Promise<unknown>;
  onEdit: (source: LogSource) => void;
  onDelete: (id: number) => Promise<unknown>;
  onToggleEnabled: (id: number, enabled: boolean) => Promise<unknown>;
  onToggleLive: (id: number, live: boolean) => Promise<unknown>;
  onAdd: () => void;
}

export function SourceGrid({ sources, onTest, onEdit, onDelete, onToggleEnabled, onToggleLive, onAdd }: SourceGridProps) {
  const [busyId, setBusyId] = useState<number | null>(null);

  const withBusy = async (id: number, action: () => Promise<unknown>) => {
    setBusyId(id);
    try {
      await action();
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="source-grid">
      {sources.map((source) => (
        <div className={`source-card${source.enabled ? "" : " source-card-disabled"}`} key={source.id}>
          <span className="source-card-corner tl" aria-hidden="true" />
          <span className="source-card-corner tr" aria-hidden="true" />
          <span className="source-card-corner bl" aria-hidden="true" />
          <span className="source-card-corner br" aria-hidden="true" />
          <div className="source-card-actions">
            <button
              type="button"
              className="btn btn-icon btn-ghost"
              title="Test connection"
              disabled={busyId === source.id}
              onClick={() => withBusy(source.id, () => onTest(source.id))}
            >
              <TestIcon />
            </button>
            <button type="button" className="btn btn-icon btn-ghost" title="Edit" onClick={() => onEdit(source)}>
              <EditIcon />
            </button>
            <button
              type="button"
              className="btn btn-icon btn-ghost"
              title="Delete"
              disabled={busyId === source.id}
              onClick={() => withBusy(source.id, () => onDelete(source.id))}
            >
              <DeleteIcon />
            </button>
          </div>
          <div className="source-card-kicker-row">
            <div className="source-card-kicker">{TYPE_LABELS[source.type]}</div>
            {source.live ? (
              <span className="live-badge">
                <span className="live-dot" />
                LIVE
              </span>
            ) : (
              source.changedFiles.length > 0 && (
                <span
                  className="update-badge"
                  title={`${source.changedFiles.join(", ")} modified — click Reload to see it`}
                >
                  <span className="update-dot" />
                  NEW DATA
                </span>
              )
            )}
          </div>
          <div className="source-card-title">{source.name}</div>
          <div className="source-card-location">{locationOf(source)}</div>
          <div className="source-card-meta">
            <StatusChip status={source.status} />
            <span>{formatRelativeTime(source.lastCheckedAt)}</span>
          </div>
          <div className="source-card-switches">
            <label
              className="switch-row"
              title={source.enabled ? "Disable source" : "Enable source"}
              onClick={(e) => e.stopPropagation()}
            >
              <span className="switch">
                <input
                  type="checkbox"
                  checked={source.enabled}
                  disabled={busyId === source.id}
                  onChange={() => withBusy(source.id, () => onToggleEnabled(source.id, !source.enabled))}
                />
                <span className="switch-slider" />
              </span>
              <span className="switch-row-label">Enabled</span>
            </label>
            <label
              className="switch-row"
              title={source.live ? "Stop watching live" : "Watch live — continuously refresh this source"}
              onClick={(e) => e.stopPropagation()}
            >
              <span className="switch switch-live">
                <input
                  type="checkbox"
                  checked={source.live}
                  disabled={busyId === source.id}
                  onChange={() => withBusy(source.id, () => onToggleLive(source.id, !source.live))}
                />
                <span className="switch-slider" />
              </span>
              <span className="switch-row-label">Live</span>
            </label>
          </div>
        </div>
      ))}

      <button type="button" className="source-card source-card-add" onClick={onAdd}>
        <PlusIcon />
        <span>Add source</span>
      </button>
    </div>
  );
}

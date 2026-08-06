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
  onAdd: () => void;
}

export function SourceGrid({ sources, onTest, onEdit, onDelete, onAdd }: SourceGridProps) {
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
        <div className="source-card" key={source.id}>
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
          <div className="source-card-kicker">{TYPE_LABELS[source.type]}</div>
          <div className="source-card-title">{source.name}</div>
          <div className="source-card-location">{locationOf(source)}</div>
          <div className="source-card-meta">
            <StatusChip status={source.status} />
            <span>{formatRelativeTime(source.lastCheckedAt)}</span>
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

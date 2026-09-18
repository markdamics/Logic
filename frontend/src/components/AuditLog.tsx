import { Fragment, useState } from "react";
import type { AuditAction, AuditEntityType, AuditLogEntry } from "../api/types";
import { AuditIcon } from "./icons";

interface AuditLogProps {
  entries: AuditLogEntry[];
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}

const ACTION_LABELS: Record<AuditAction, string> = {
  CREATE: "Created",
  UPDATE: "Updated",
  DELETE: "Deleted",
};

const ACTION_CHIP_CLASS: Record<AuditAction, string> = {
  CREATE: "status-reachable",
  UPDATE: "status-unverified",
  DELETE: "status-unreachable",
};

const ENTITY_LABELS: Record<AuditEntityType, string> = {
  LOG_SOURCE: "Source",
  ALERT_RULE: "Alert rule",
  REDACTION_RULE: "Redaction rule",
};

/** LOGIC-110: read-only view over every create/update/delete on a LogSource, AlertRule, or RedactionRule - see AuditLogController (no write endpoint exists at all, by design). */
export function AuditLog({ entries, loading, error, onRefresh }: AuditLogProps) {
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const toggleExpand = (id: number) => {
    setExpandedId((current) => (current === id ? null : id));
  };

  return (
    <div className="alerts-screen">
      <div className="alerts-toolbar">
        <button type="button" className="btn btn-secondary" onClick={onRefresh} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {!loading && entries.length === 0 && (
        <div className="alerts-placeholder">
          <div className="alerts-placeholder-panel">
            <AuditIcon size={28} />
            <h4>No admin actions recorded yet</h4>
            <p className="text-muted">
              Every create, edit, and delete on a source, alert rule, or redaction rule is logged here with a
              timestamp, who did it, and the before/after values — a read-only trail for accountability, not
              something this screen (or the API) can edit.
            </p>
          </div>
        </div>
      )}

      {entries.length > 0 && (
        <div className="log-table-wrapper">
          <table className="log-table entries-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Entity</th>
                <th>Name</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <Fragment key={entry.id}>
                  <tr className="log-row" onClick={() => toggleExpand(entry.id)} aria-expanded={expandedId === entry.id}>
                    <td className="text-muted" style={{ whiteSpace: "nowrap" }}>
                      {new Date(entry.timestamp).toLocaleString()}
                    </td>
                    <td>{entry.actor}</td>
                    <td>
                      <span className={`status-chip ${ACTION_CHIP_CLASS[entry.action]}`}>{ACTION_LABELS[entry.action]}</span>
                    </td>
                    <td className="text-muted">{ENTITY_LABELS[entry.entityType]}</td>
                    <td>{entry.entityName}</td>
                    <td onClick={(e) => e.stopPropagation()} style={{ whiteSpace: "nowrap" }}>
                      <button type="button" className="btn btn-secondary btn-small" onClick={() => toggleExpand(entry.id)}>
                        {expandedId === entry.id ? "Hide" : "Details"}
                      </button>
                    </td>
                  </tr>
                  {expandedId === entry.id && (
                    <tr className="log-detail-row">
                      <td colSpan={6}>
                        <div className="log-detail-panel">
                          <div className="log-detail-message-label">Field values at the time of this action</div>
                          <AuditValueDiff oldValue={entry.oldValue} newValue={entry.newValue} />
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function parseSnapshot(json: string | null): Record<string, unknown> | null {
  if (!json) return null;
  try {
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function formatFieldValue(value: unknown): string {
  if (value === undefined || value === null) return "—";
  if (Array.isArray(value)) return value.length > 0 ? value.join(", ") : "—";
  if (typeof value === "boolean") return value ? "true" : "false";
  return String(value);
}

function AuditValueDiff({ oldValue, newValue }: { oldValue: string | null; newValue: string | null }) {
  const before = parseSnapshot(oldValue);
  const after = parseSnapshot(newValue);
  const keys = Array.from(new Set([...(before ? Object.keys(before) : []), ...(after ? Object.keys(after) : [])]));

  return (
    <table className="log-table audit-diff-table">
      <thead>
        <tr>
          <th>Field</th>
          {before && <th>Before</th>}
          {after && <th>After</th>}
        </tr>
      </thead>
      <tbody>
        {keys.map((key) => {
          const beforeValue = before ? before[key] : undefined;
          const afterValue = after ? after[key] : undefined;
          const changed = before !== null && after !== null && JSON.stringify(beforeValue) !== JSON.stringify(afterValue);
          return (
            <tr key={key} className={changed ? "audit-diff-changed" : undefined}>
              <td className="text-muted">{key}</td>
              {before && <td>{formatFieldValue(beforeValue)}</td>}
              {after && <td>{formatFieldValue(afterValue)}</td>}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

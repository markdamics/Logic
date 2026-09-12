import { useMemo, useState } from "react";
import { RedactionRuleDialog } from "./RedactionRuleDialog";
import { ApiError } from "../api/client";
import type { CreateRedactionRuleRequest, LogSource, RedactionRule } from "../api/types";
import { RedactionIcon } from "./icons";
import { createLogger } from "../utils/logger";

const logger = createLogger("RedactionRules");

interface RedactionRulesProps {
  sources: LogSource[];
  redactionRules: RedactionRule[];
  loading: boolean;
  onCreate: (req: CreateRedactionRuleRequest) => Promise<RedactionRule>;
  onUpdate: (id: number, req: CreateRedactionRuleRequest) => Promise<RedactionRule>;
  onDelete: (id: number) => Promise<void>;
}

type DialogState = { mode: "add" } | { mode: "edit"; rule: RedactionRule };

export function RedactionRules({ sources, redactionRules, loading, onCreate, onUpdate, onDelete }: RedactionRulesProps) {
  const [dialogState, setDialogState] = useState<DialogState | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const sourceNames = useMemo(() => Array.from(new Set(sources.map((s) => s.name))).sort(), [sources]);

  const handleDelete = async (id: number) => {
    setActionError(null);
    setBusyId(id);
    try {
      await onDelete(id);
    } catch (e) {
      logger.warn(`Failed to delete redaction rule ${id}`, e);
      setActionError(e instanceof ApiError ? e.message : "Failed to delete redaction rule");
    } finally {
      setBusyId(null);
    }
  };

  const handleToggleEnabled = async (rule: RedactionRule) => {
    setActionError(null);
    setBusyId(rule.id);
    try {
      await onUpdate(rule.id, {
        name: rule.name,
        pattern: rule.pattern,
        replacement: rule.replacement ?? undefined,
        source: rule.source ?? undefined,
        enabled: !rule.enabled,
      });
    } catch (e) {
      logger.warn(`Failed to ${rule.enabled ? "disable" : "enable"} redaction rule ${rule.id}`, e);
      setActionError(e instanceof ApiError ? e.message : "Failed to update redaction rule");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="alerts-screen">
      <div className="alerts-toolbar">
        <button type="button" className="btn btn-primary" onClick={() => setDialogState({ mode: "add" })}>
          Add redaction rule
        </button>
      </div>

      {actionError && <div className="error-banner">{actionError}</div>}

      {!loading && redactionRules.length === 0 && (
        <div className="alerts-placeholder">
          <div className="alerts-placeholder-panel">
            <RedactionIcon size={28} />
            <h4>No redaction rules yet</h4>
            <p className="text-muted">
              Add a regex-based rule to mask emails, credit card numbers, API keys, or any other sensitive text in
              ingested log messages before they're stored or displayed. Off by default — nothing is masked until you
              add a rule.
            </p>
          </div>
        </div>
      )}

      {redactionRules.length > 0 && (
        <div className="log-table-wrapper">
          <table className="log-table entries-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Pattern</th>
                <th>Mask</th>
                <th>Scope</th>
                <th>State</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {redactionRules.map((rule) => (
                <tr key={rule.id} className="log-row">
                  <td>{rule.name}</td>
                  <td className="text-muted" title={rule.pattern}>
                    <code>{rule.pattern}</code>
                  </td>
                  <td className="text-muted">{rule.replacement || "***"}</td>
                  <td className="text-muted">{rule.source || "global"}</td>
                  <td>
                    <span className={`status-chip ${rule.enabled ? "status-reachable" : "status-disabled"}`}>
                      {rule.enabled ? "Enabled" : "Disabled"}
                    </span>
                  </td>
                  <td style={{ whiteSpace: "nowrap" }}>
                    <button
                      type="button"
                      className="btn btn-secondary btn-small"
                      disabled={busyId === rule.id}
                      onClick={() => handleToggleEnabled(rule)}
                    >
                      {rule.enabled ? "Disable" : "Enable"}
                    </button>{" "}
                    <button type="button" className="btn btn-secondary btn-small" onClick={() => setDialogState({ mode: "edit", rule })}>
                      Edit
                    </button>{" "}
                    <button
                      type="button"
                      className="btn btn-danger btn-small"
                      disabled={busyId === rule.id}
                      onClick={() => handleDelete(rule.id)}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {dialogState && (
        <RedactionRuleDialog
          rule={dialogState.mode === "edit" ? dialogState.rule : undefined}
          sourceNames={sourceNames}
          onClose={() => setDialogState(null)}
          onSubmit={(req) => (dialogState.mode === "edit" ? onUpdate(dialogState.rule.id, req) : onCreate(req))}
        />
      )}
    </div>
  );
}

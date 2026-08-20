import { Fragment, useMemo, useState } from "react";
import { AlertRuleDialog } from "./AlertRuleDialog";
import { ApiError } from "../api/client";
import type { AlertEvent, AlertRule, CreateAlertRuleRequest, LogSource } from "../api/types";
import { formatRelativeTime } from "../utils/time";
import { createLogger } from "../utils/logger";
import { AlertsIcon } from "./icons";

const logger = createLogger("Alerts");

interface AlertsProps {
  sources: LogSource[];
  alertRules: AlertRule[];
  loading: boolean;
  onCreate: (req: CreateAlertRuleRequest) => Promise<AlertRule>;
  onUpdate: (id: number, req: CreateAlertRuleRequest) => Promise<AlertRule>;
  onDelete: (id: number) => Promise<void>;
  onToggleMuted: (id: number, muted: boolean) => Promise<void>;
  onFetchEvents: (id: number) => Promise<AlertEvent[]>;
}

type DialogState = { mode: "add" } | { mode: "edit"; rule: AlertRule };

function describeScope(rule: AlertRule): string {
  const scope = [rule.source ? `source=${rule.source}` : null, rule.file ? `file=${rule.file}` : null]
    .filter(Boolean)
    .join(" ");
  const core =
    rule.queryLanguage === "SIMPLE"
      ? `${rule.search || "(any text)"}${rule.levels.length > 0 ? ` [${rule.levels.join(", ")}]` : ""}`
      : `${rule.queryLanguage}: ${rule.query}`;
  return scope ? `${core} · ${scope}` : core;
}

function describeCondition(rule: AlertRule): string {
  if (rule.ruleType === "THRESHOLD") {
    const op = rule.comparisonOp === "GTE" ? "≥" : ">";
    return `${rule.metric.toLowerCase()} ${op} ${rule.threshold} / ${rule.windowMinutes}m`;
  }
  return `${rule.metric.toLowerCase()} > baseline + ${rule.anomalyStdDevMultiplier}σ (${rule.anomalyBaselineWindows}×${rule.windowMinutes}m)`;
}

export function Alerts({ sources, alertRules, loading, onCreate, onUpdate, onDelete, onToggleMuted, onFetchEvents }: AlertsProps) {
  const [dialogState, setDialogState] = useState<DialogState | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [events, setEvents] = useState<AlertEvent[]>([]);
  const [eventsLoading, setEventsLoading] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const sourceNames = useMemo(() => Array.from(new Set(sources.map((s) => s.name))).sort(), [sources]);

  const toggleExpand = async (rule: AlertRule) => {
    if (expandedId === rule.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(rule.id);
    setEventsLoading(true);
    try {
      setEvents(await onFetchEvents(rule.id));
    } catch (e) {
      logger.warn(`Failed to load events for alert rule ${rule.id}`, e);
    } finally {
      setEventsLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    setActionError(null);
    setBusyId(id);
    try {
      await onDelete(id);
      if (expandedId === id) setExpandedId(null);
    } catch (e) {
      logger.warn(`Failed to delete alert rule ${id}`, e);
      setActionError(e instanceof ApiError ? e.message : "Failed to delete alert rule");
    } finally {
      setBusyId(null);
    }
  };

  const handleToggleMuted = async (rule: AlertRule) => {
    setActionError(null);
    setBusyId(rule.id);
    try {
      await onToggleMuted(rule.id, !rule.muted);
    } catch (e) {
      logger.warn(`Failed to ${rule.muted ? "unmute" : "mute"} alert rule ${rule.id}`, e);
      setActionError(e instanceof ApiError ? e.message : "Failed to update alert rule");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="alerts-screen">
      <div className="alerts-toolbar">
        <button type="button" className="btn btn-primary" onClick={() => setDialogState({ mode: "add" })}>
          Arm new rule
        </button>
      </div>

      {actionError && <div className="error-banner">{actionError}</div>}

      {!loading && alertRules.length === 0 && (
        <div className="alerts-placeholder">
          <div className="alerts-placeholder-panel">
            <AlertsIcon size={28} />
            <h4>No alert rules yet</h4>
            <p className="text-muted">
              Arm a rule to watch for error spikes, specific patterns, or statistically anomalous volume — and
              optionally fire a webhook to your incident tooling when it triggers.
            </p>
          </div>
        </div>
      )}

      {alertRules.length > 0 && (
        <div className="log-table-wrapper">
          <table className="log-table entries-table">
            <thead>
              <tr>
                <th>Rule</th>
                <th>Scope</th>
                <th>Condition</th>
                <th>State</th>
                <th>Last trigger</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {alertRules.map((rule) => (
                <Fragment key={rule.id}>
                  <tr className="log-row" onClick={() => toggleExpand(rule)} aria-expanded={expandedId === rule.id}>
                    <td>{rule.name}</td>
                    <td className="text-muted" title={describeScope(rule)}>
                      {describeScope(rule)}
                    </td>
                    <td className="text-muted">{describeCondition(rule)}</td>
                    <td>
                      <span
                        className={`status-chip ${rule.muted ? "status-disabled" : "status-reachable"}`}
                      >
                        {rule.muted ? "Muted" : "Armed"}
                      </span>
                    </td>
                    <td className="text-muted">{rule.lastTriggeredAt ? formatRelativeTime(rule.lastTriggeredAt) : "—"}</td>
                    <td onClick={(e) => e.stopPropagation()} style={{ whiteSpace: "nowrap" }}>
                      <button
                        type="button"
                        className="btn btn-secondary btn-small"
                        disabled={busyId === rule.id}
                        onClick={() => handleToggleMuted(rule)}
                      >
                        {rule.muted ? "Unmute" : "Mute"}
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
                  {expandedId === rule.id && (
                    <tr className="log-detail-row">
                      <td colSpan={6}>
                        <div className="log-detail-panel">
                          <div className="log-detail-message-label">Recent trigger history</div>
                          {eventsLoading && <div className="log-empty">Loading…</div>}
                          {!eventsLoading && events.length === 0 && <div className="log-empty">Never triggered.</div>}
                          {!eventsLoading && events.length > 0 && (
                            <table className="log-table">
                              <thead>
                                <tr>
                                  <th>Triggered</th>
                                  <th>Resolved</th>
                                  <th>Value</th>
                                  <th>Webhook</th>
                                </tr>
                              </thead>
                              <tbody>
                                {events.map((event) => (
                                  <tr key={event.id}>
                                    <td>{new Date(event.triggeredAt).toLocaleString()}</td>
                                    <td>{event.resolvedAt ? new Date(event.resolvedAt).toLocaleString() : "still open"}</td>
                                    <td>{event.metricValue}</td>
                                    <td>{event.webhookStatus ?? "—"}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
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

      {dialogState && (
        <AlertRuleDialog
          rule={dialogState.mode === "edit" ? dialogState.rule : undefined}
          sourceNames={sourceNames}
          onClose={() => setDialogState(null)}
          onSubmit={(req) => (dialogState.mode === "edit" ? onUpdate(dialogState.rule.id, req) : onCreate(req))}
        />
      )}
    </div>
  );
}

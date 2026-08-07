import { useEffect, useState } from "react";
import { fetchDashboardSummary } from "../api/client";
import type { DashboardSummary, LogSource } from "../api/types";
import { formatRelativeTime } from "../utils/time";
import { createLogger } from "../utils/logger";
import { updateMessageFor } from "../utils/source";
import { StatusChip } from "./StatusChip";

const logger = createLogger("Dashboard");

const LIVE_POLL_INTERVAL_MS = 3000;

interface DashboardProps {
  sources: LogSource[];
  reloadSignal?: number;
  onReload?: () => void;
}

export function Dashboard({ sources, reloadSignal, onReload }: DashboardProps) {
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [liveTick, setLiveTick] = useState(0);

  const hasLiveSource = sources.some((s) => s.enabled && s.live);

  // While any source is live, keep polling so the dashboard updates on its own.
  useEffect(() => {
    if (!hasLiveSource) {
      return;
    }
    const timer = setInterval(() => setLiveTick((t) => t + 1), LIVE_POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [hasLiveSource]);

  useEffect(() => {
    if (sources.length === 0) {
      setSummary(null);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    fetchDashboardSummary()
      .then((result) => {
        if (cancelled) return;
        setSummary(result);
      })
      .catch((e) => {
        if (cancelled) return;
        logger.error("Failed to load dashboard summary", e);
        setError(e instanceof Error ? e.message : "Failed to load dashboard summary");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [sources.length, liveTick, reloadSignal]);

  if (sources.length === 0) {
    return (
      <div className="coming-soon">
        <p>No log sources configured yet — add one from the Sources screen to see your dashboard here.</p>
      </div>
    );
  }

  if (error) {
    return <div className="error-banner">{error}</div>;
  }

  if (loading && !summary) {
    return <div className="log-empty">Loading dashboard…</div>;
  }

  if (!summary) {
    return null;
  }

  const topErrorFiles = summary.fileActivity
    .filter((a) => a.errorsLast24h > 0)
    .sort((a, b) => b.errorsLast24h - a.errorsLast24h)
    .slice(0, 6);
  const maxErrors = Math.max(...topErrorFiles.map((a) => a.errorsLast24h), 1);
  const sourcesWithUpdates = sources.filter((s) => s.changedFiles.length > 0);

  return (
    <div className="dashboard">
      {hasLiveSource && (
        <span className="live-badge dashboard-live-badge" title="Auto-refreshing while a live source is watched">
          <span className="live-dot" />
          LIVE
        </span>
      )}

      {sourcesWithUpdates.length > 0 && (
        <button type="button" className="update-banner" onClick={onReload}>
          <span className="update-dot" />
          <span className="update-banner-text">
            {sourcesWithUpdates.map(updateMessageFor).join("; ")}
          </span>
          <span className="update-banner-action">Reload to see the changes</span>
        </button>
      )}

      <div className="stat-grid">
        <div className="stat-card">
          <div className="stat-card-label">Sources</div>
          <div className="stat-card-value">{summary.totalSources}</div>
          <div className="stat-card-sub">{summary.reachableSources} reachable</div>
        </div>
        <div className="stat-card">
          <div className="stat-card-label">Log entries (24h)</div>
          <div className="stat-card-value">{summary.entriesLast24h.toLocaleString()}</div>
        </div>
        <div className="stat-card stat-card-error">
          <div className="stat-card-label">Errors (24h)</div>
          <div className="stat-card-value">{summary.errorsLast24h.toLocaleString()}</div>
        </div>
        <div className="stat-card stat-card-warning">
          <div className="stat-card-label">Warnings (24h)</div>
          <div className="stat-card-value">{summary.warningsLast24h.toLocaleString()}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card-label">Enabled sources</div>
          <div className="stat-card-value">
            {summary.enabledSources} / {summary.totalSources}
          </div>
          <div className="stat-card-sub">{summary.disabledSources} disabled</div>
        </div>
      </div>

      <div className="dashboard-columns">
        <section className="dashboard-section">
          <h4 className="chart-heading">Errors by file (24h)</h4>
          {topErrorFiles.length === 0 ? (
            <div className="log-empty">No errors in the last 24h.</div>
          ) : (
            <div className="bar-chart">
              {topErrorFiles.map((activity) => (
                <div className="bar-chart-col" key={`${activity.source}/${activity.file}`}>
                  <div className="bar-chart-plot">
                    <div
                      className="bar-chart-bar"
                      style={{ height: `${(activity.errorsLast24h / maxErrors) * 100}%` }}
                      title={`${activity.source} · ${activity.file}: ${activity.errorsLast24h} errors`}
                    />
                  </div>
                  <div className="bar-chart-label" title={`${activity.source} · ${activity.file}`}>
                    {activity.file}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="dashboard-section">
          <h4>Source activity (24h)</h4>
          <div className="log-table-wrapper">
            <table className="log-table">
              <thead>
                <tr>
                  <th>Source</th>
                  <th>Status</th>
                  <th>Entries</th>
                  <th>Errors</th>
                </tr>
              </thead>
              <tbody>
                {summary.sourceActivity.map((activity) => (
                  <tr key={activity.source} className={activity.enabled ? "" : "row-disabled"}>
                    <td>
                      {activity.source}
                      {activity.live && (
                        <span className="live-badge activity-live-badge">
                          <span className="live-dot" />
                          LIVE
                        </span>
                      )}
                    </td>
                    <td>
                      {activity.enabled ? (
                        <StatusChip status={activity.status} />
                      ) : (
                        <span className="status-chip status-disabled">Disabled</span>
                      )}
                    </td>
                    <td>{activity.entriesLast24h.toLocaleString()}</td>
                    <td>{activity.errorsLast24h.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="dashboard-section">
          <h4>Recent issues</h4>
          <ul className="issue-list">
            {summary.recentIssues.map((entry) => (
              <li className="issue-item" key={entry.id}>
                <span className={`level-chip level-${entry.level.toLowerCase()}`}>{entry.level}</span>
                <div className="issue-body">
                  <div className="issue-message">{entry.message}</div>
                  <div className="issue-meta text-muted">
                    {entry.source}
                    {entry.file ? ` · ${entry.file}` : ""} · {formatRelativeTime(entry.timestamp)}
                  </div>
                </div>
              </li>
            ))}
            {summary.recentIssues.length === 0 && (
              <li className="log-empty">No errors or warnings recently.</li>
            )}
          </ul>
        </section>
      </div>
    </div>
  );
}

import { Fragment, useEffect, useMemo, useState } from "react";
import { listLogFiles } from "../api/client";
import { Sparkline } from "./Sparkline";
import { LogsIcon, PatternsIcon, TestIcon } from "./icons";
import type { LogSource, LogTemplate, TemplateSort } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("Patterns");

interface PatternsProps {
  sources: LogSource[];
  templates: LogTemplate[];
  loading: boolean;
  source: string | undefined;
  file: string | undefined;
  sort: TemplateSort;
  onSourceChange: (source: string | undefined) => void;
  onFileChange: (file: string | undefined) => void;
  onSortChange: (sort: TemplateSort) => void;
  onRefresh: () => Promise<void>;
  onViewMatchingLines: (template: LogTemplate) => void;
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function Patterns({
  sources,
  templates,
  loading,
  source,
  file,
  sort,
  onSourceChange,
  onFileChange,
  onSortChange,
  onRefresh,
  onViewMatchingLines,
}: PatternsProps) {
  const [refreshing, setRefreshing] = useState(false);

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  };

  // Only offer sources that currently have pattern mining enabled - picking a disabled one
  // would always come back empty (the backend hides stale rows from before it was toggled
  // off), which would just be a confusing dead end here.
  const sourceNames = useMemo(
    () => Array.from(new Set(sources.filter((s) => s.patternMiningEnabled).map((s) => s.name))).sort(),
    [sources],
  );
  const [fileOptions, setFileOptions] = useState<string[]>([]);
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  // Refresh the file-filter dropdown whenever the source scope changes, mirroring Log Stream's
  // own source->file cascade (see LogStream.tsx) - the same /api/logs/files endpoint already
  // knows which files exist for a source, regardless of whether patterns have been mined yet.
  useEffect(() => {
    if (sources.length === 0) {
      setFileOptions([]);
      return;
    }
    let cancelled = false;
    listLogFiles(source)
      .then((files) => {
        if (cancelled) return;
        setFileOptions(files);
      })
      .catch((e) => {
        logger.warn("Failed to load file filter options", e);
      });
    return () => {
      cancelled = true;
    };
  }, [sources.length, source]);

  const showSourceColumn = !source;
  const showFileColumn = !file;
  const columnCount = 7 + (showSourceColumn ? 1 : 0) + (showFileColumn ? 1 : 0);

  const toggleExpand = (id: number) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  return (
    <div className="alerts-screen">
      <div className="alerts-toolbar">
        <select className="input" value={source ?? ""} onChange={(e) => onSourceChange(e.target.value || undefined)}>
          <option value="">All sources</option>
          {sourceNames.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
        <select className="input" value={file ?? ""} onChange={(e) => onFileChange(e.target.value || undefined)}>
          <option value="">All files</option>
          {fileOptions.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
        <select className="input" value={sort} onChange={(e) => onSortChange(e.target.value as TemplateSort)}>
          <option value="volume">Sort by volume</option>
          <option value="recent">Sort by most recently new</option>
        </select>
        <button type="button" className="btn btn-secondary btn-small" onClick={handleRefresh} disabled={refreshing}>
          <TestIcon size={13} />
          {refreshing ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {!loading && templates.length === 0 && (
        <div className="alerts-placeholder">
          <div className="alerts-placeholder-panel">
            <PatternsIcon size={28} />
            <h4>No patterns mined yet</h4>
            <p className="text-muted">
              Repeated log message shapes are clustered here automatically once a source has pattern mining enabled
              (toggle it from the Sources screen) — no manual grepping required. Give it a few index cycles once a
              source has traffic.
            </p>
          </div>
        </div>
      )}

      {templates.length > 0 && (
        <div className="log-table-wrapper">
          <table className="log-table entries-table">
            <thead>
              <tr>
                <th>Template</th>
                {showSourceColumn && <th style={{ width: "10%" }}>Source</th>}
                {showFileColumn && <th style={{ width: "12%" }}>File</th>}
                <th style={{ width: "9%" }}>Occurrences</th>
                <th style={{ width: "9%" }}>Trend</th>
                <th style={{ width: "13%" }}>First seen</th>
                <th style={{ width: "13%" }}>Last seen</th>
                <th>Sample</th>
                <th style={{ width: "44px" }} />
              </tr>
            </thead>
            <tbody>
              {templates.map((template) => {
                const isExpanded = expandedIds.has(template.id);
                return (
                  <Fragment key={template.id}>
                    <tr
                      className={`log-row${isExpanded ? " expanded" : ""}`}
                      onClick={() => toggleExpand(template.id)}
                      aria-expanded={isExpanded}
                    >
                      <td className="log-message-truncated">
                        <span className="log-expand-chevron">▸</span>
                        <code>{template.templateText}</code>
                      </td>
                      {showSourceColumn && <td className="text-muted">{template.source}</td>}
                      {showFileColumn && <td className="text-muted">{template.file ?? "—"}</td>}
                      <td>{template.occurrenceCount.toLocaleString()}</td>
                      <td>
                        <Sparkline values={template.history} />
                      </td>
                      <td className="text-muted">{formatTimestamp(template.firstSeenAt)}</td>
                      <td className="text-muted">{formatTimestamp(template.lastSeenAt)}</td>
                      <td className="text-muted log-message-truncated">{template.sampleRawLine}</td>
                      <td>
                        <button
                          type="button"
                          className="btn btn-icon btn-ghost"
                          title={`View matching lines - show the ${template.occurrenceCount.toLocaleString()} log lines matching this pattern`}
                          aria-label="View matching lines"
                          onClick={(e) => {
                            e.stopPropagation();
                            onViewMatchingLines(template);
                          }}
                        >
                          <LogsIcon size={14} />
                        </button>
                      </td>
                    </tr>
                    {isExpanded && (
                      <tr className="log-detail-row">
                        <td colSpan={columnCount}>
                          <div className="log-detail-panel">
                            <div>
                              <div className="log-detail-message-label">Template</div>
                              <pre className="log-detail-message">{template.templateText}</pre>
                            </div>
                            <div>
                              <div className="log-detail-message-label">Sample raw line</div>
                              <pre className="log-detail-message">{template.sampleRawLine}</pre>
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

import { useEffect, useMemo, useState } from "react";
import { fetchLogs, listLogFiles, queryLogs } from "../api/client";
import type {
  LogAggregationResult,
  LogEntry,
  LogLevel,
  LogQueryResult,
  LogSource,
  QueryLanguage,
} from "../api/types";
import { createLogger } from "../utils/logger";
import { parseMessage } from "../utils/messageParser";
import { updateMessageFor } from "../utils/source";
import { BarChart } from "./BarChart";

const logger = createLogger("LogStream");

type SortColumn = "time" | "level" | "source" | "file";
type SortDirection = "asc" | "desc";
type FilterMode = "simple" | "query";

const QUERY_LANGUAGES: { value: QueryLanguage; label: string; placeholder: string }[] = [
  { value: "LUCENE", label: "Lucene", placeholder: "level:ERROR AND source:payments-api" },
  { value: "SPL", label: "SPL", placeholder: 'level=ERROR AND source=payments-api | stats count by source' },
  { value: "LOGQL", label: "LogQL", placeholder: '{level="ERROR"} |= "timeout"' },
];

const LEVELS: LogLevel[] = ["ERROR", "WARN", "INFO", "DEBUG"];
const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];
const DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];
const SEARCH_DEBOUNCE_MS = 300;
const LIVE_POLL_INTERVAL_MS = 3000;

const TIME_RANGES: { value: string; label: string; minutes: number }[] = [
  { value: "15m", label: "Last 15 min", minutes: 15 },
  { value: "1h", label: "Last 1 hour", minutes: 60 },
  { value: "24h", label: "Last 24 hours", minutes: 24 * 60 },
  { value: "7d", label: "Last 7 days", minutes: 7 * 24 * 60 },
  { value: "0", label: "All time", minutes: 0 },
];

interface Preset {
  id: string;
  label: string;
  severities: LogLevel[];
  search: string;
}

const PRESETS: Preset[] = [
  { id: "all-errors", label: "All errors", severities: ["ERROR"], search: "" },
  { id: "errors-warnings", label: "Errors & warnings", severities: ["ERROR", "WARN"], search: "" },
  { id: "clear", label: "Clear filters", severities: [], search: "" },
];

interface LogStreamProps {
  sources: LogSource[];
  onCountChange?: (count: number) => void;
  reloadSignal?: number;
  onReload?: () => void;
}

export function LogStream({ sources, onCountChange, reloadSignal, onReload }: LogStreamProps) {
  const [mode, setMode] = useState<FilterMode>("simple");
  const [searchInput, setSearchInput] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [queryInput, setQueryInput] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [queryLanguage, setQueryLanguage] = useState<QueryLanguage>("LUCENE");
  const [sourceFilter, setSourceFilter] = useState("");
  const [fileFilter, setFileFilter] = useState("");
  const [fileOptions, setFileOptions] = useState<string[]>([]);
  const [timeRange, setTimeRange] = useState("24h");
  const [severities, setSeverities] = useState<Set<LogLevel>>(new Set());
  const [sortColumn, setSortColumn] = useState<SortColumn>("time");
  const [sortDirection, setSortDirection] = useState<SortDirection>("desc");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [liveTick, setLiveTick] = useState(0);
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  const [data, setData] = useState<LogQueryResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const sourceNames = useMemo(() => Array.from(new Set(sources.map((s) => s.name))).sort(), [sources]);
  const rangeMinutes = TIME_RANGES.find((r) => r.value === timeRange)?.minutes ?? 24 * 60;
  const hasLiveSource = sources.some((s) => s.enabled && s.live);
  const sourcesWithUpdates = sources.filter((s) => s.changedFiles.length > 0);

  // While any source is live, keep polling so the table updates on its own.
  useEffect(() => {
    if (!hasLiveSource) {
      return;
    }
    const timer = setInterval(() => setLiveTick((t) => t + 1), LIVE_POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [hasLiveSource]);

  // Refresh the file-filter dropdown whenever the source scope changes, and
  // drop any file selection that's no longer valid for the new scope.
  useEffect(() => {
    if (sources.length === 0) {
      setFileOptions([]);
      return;
    }
    let cancelled = false;
    listLogFiles(sourceFilter || undefined)
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
  }, [sources.length, sourceFilter]);

  // Debounce free-text search so we don't hit the backend on every keystroke.
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(searchInput);
      setPage(0);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Same debounce for the query-bar input.
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(queryInput);
      setPage(0);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [queryInput]);

  useEffect(() => {
    if (sources.length === 0) {
      setData(null);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    const scope = {
      source: sourceFilter || undefined,
      file: fileFilter || undefined,
      rangeMinutes,
      sortBy: sortColumn,
      sortDir: sortDirection,
      page,
      size: pageSize,
    };

    const request =
      mode === "query"
        ? queryLogs({ q: debouncedQuery, queryLanguage, ...scope })
        : fetchLogs({
            search: debouncedSearch || undefined,
            levels: severities.size > 0 ? Array.from(severities) : undefined,
            ...scope,
          });
    logger.debug("Querying logs", { mode, ...scope });

    request
      .then((result) => {
        if (cancelled) return;
        setData(result);
      })
      .catch((e) => {
        if (cancelled) return;
        logger.error("Log query failed", e);
        setError(e instanceof Error ? e.message : "Failed to load log entries");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [
    sources.length,
    mode,
    debouncedSearch,
    debouncedQuery,
    queryLanguage,
    severities,
    sourceFilter,
    fileFilter,
    rangeMinutes,
    sortColumn,
    sortDirection,
    page,
    pageSize,
    liveTick,
    reloadSignal,
  ]);

  useEffect(() => {
    onCountChange?.(data?.totalElements ?? 0);
  }, [data, onCountChange]);

  // Collapse expanded rows whenever the filtered/sorted row set changes
  // underneath them (but not on a live-poll refresh of the same page).
  useEffect(() => {
    setExpandedIds(new Set());
  }, [
    mode,
    debouncedSearch,
    debouncedQuery,
    queryLanguage,
    severities,
    sourceFilter,
    fileFilter,
    rangeMinutes,
    sortColumn,
    sortDirection,
    page,
    pageSize,
  ]);

  const toggleSeverity = (level: LogLevel) => {
    setSeverities((prev) => {
      const next = new Set(prev);
      if (next.has(level)) {
        next.delete(level);
      } else {
        next.add(level);
      }
      return next;
    });
    setPage(0);
  };

  const applyPreset = (preset: Preset) => {
    setMode("simple");
    setSeverities(new Set(preset.severities));
    setSearchInput(preset.search);
    setDebouncedSearch(preset.search);
    setPage(0);
  };

  const toggleSort = (column: SortColumn) => {
    setSortDirection((prevDir) => (sortColumn === column ? (prevDir === "asc" ? "desc" : "asc") : "asc"));
    setSortColumn(column);
    setPage(0);
  };

  const sortArrow = (column: SortColumn) =>
    sortColumn === column ? (sortDirection === "asc" ? "▲" : "▼") : "";

  const handlePageSizeChange = (size: number) => {
    setPageSize(size);
    setPage(0);
  };

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

  if (sources.length === 0) {
    return (
      <div className="coming-soon">
        <p>No log sources configured yet — add one from the Sources screen to see its log stream here.</p>
      </div>
    );
  }

  const rows = data?.content ?? [];
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const currentPage = data?.page ?? page;

  return (
    <div className="log-stream">
      <div className="log-filters">
        <div className="log-mode-toggle">
          <button
            type="button"
            className={`log-mode-btn${mode === "simple" ? " active" : ""}`}
            onClick={() => setMode("simple")}
          >
            Simple
          </button>
          <button
            type="button"
            className={`log-mode-btn${mode === "query" ? " active" : ""}`}
            onClick={() => setMode("query")}
          >
            Query
          </button>
        </div>
        {mode === "simple" ? (
          <input
            className="input log-search"
            placeholder="Search logs…"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
        ) : (
          <>
            <input
              className="input log-search log-query-input"
              placeholder={QUERY_LANGUAGES.find((lang) => lang.value === queryLanguage)?.placeholder}
              value={queryInput}
              onChange={(e) => setQueryInput(e.target.value)}
            />
            <select
              className="input"
              value={queryLanguage}
              onChange={(e) => {
                // A query string in one language's syntax is essentially
                // never valid in another - clear it rather than surface a
                // confusing parse error against leftover text.
                setQueryLanguage(e.target.value as QueryLanguage);
                setQueryInput("");
                setDebouncedQuery("");
              }}
            >
              {QUERY_LANGUAGES.map((lang) => (
                <option key={lang.value} value={lang.value}>
                  {lang.label}
                </option>
              ))}
            </select>
          </>
        )}
        <select
          className="input"
          value={sourceFilter}
          onChange={(e) => {
            setSourceFilter(e.target.value);
            setFileFilter("");
            setPage(0);
          }}
        >
          <option value="">All sources</option>
          {sourceNames.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
        <select
          className="input"
          value={fileFilter}
          onChange={(e) => {
            setFileFilter(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All files</option>
          {fileOptions.map((file) => (
            <option key={file} value={file}>
              {file}
            </option>
          ))}
        </select>
        <select
          className="input"
          value={timeRange}
          onChange={(e) => {
            setTimeRange(e.target.value);
            setPage(0);
          }}
        >
          {TIME_RANGES.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>
        <div className="log-severity-chips">
          {mode === "simple" && LEVELS.map((level) => (
            <button
              key={level}
              type="button"
              className={`chip-toggle level-${level.toLowerCase()}${severities.has(level) ? " active" : ""}`}
              onClick={() => toggleSeverity(level)}
            >
              {level}
            </button>
          ))}
        </div>
        {hasLiveSource && (
          <span className="live-badge" title="Auto-refreshing while a live source is watched">
            <span className="live-dot" />
            LIVE
          </span>
        )}
      </div>

      <div className="log-presets">
        <span className="log-presets-label">Presets</span>
        {PRESETS.map((preset) => (
          <button key={preset.id} type="button" className="preset-btn" onClick={() => applyPreset(preset)}>
            {preset.label}
          </button>
        ))}
      </div>

      {error && <div className="error-banner">{error}</div>}

      {sourcesWithUpdates.length > 0 && (
        <button type="button" className="update-banner" onClick={onReload}>
          <span className="update-dot" />
          <span className="update-banner-text">
            {sourcesWithUpdates.map(updateMessageFor).join("; ")}
          </span>
          <span className="update-banner-action">Reload to see the changes</span>
        </button>
      )}

      {data?.aggregation ? (
        <AggregationView aggregation={data.aggregation} />
      ) : (
        <>
          <div className="log-table-wrapper">
            <table className="log-table entries-table">
              <colgroup>
                <col style={{ width: "110px" }} />
                <col style={{ width: "110px" }} />
                <col style={{ width: "90px" }} />
                <col style={{ width: "160px" }} />
                <col style={{ width: "auto" }} />
              </colgroup>
              <thead>
                <tr>
                  <th onClick={() => toggleSort("time")}>Date {sortArrow("time")}</th>
                  <th onClick={() => toggleSort("time")}>Time {sortArrow("time")}</th>
                  <th onClick={() => toggleSort("level")}>Level {sortArrow("level")}</th>
                  <th onClick={() => toggleSort("file")}>File {sortArrow("file")}</th>
                  <th>Message</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((entry) => {
                  const isExpanded = expandedIds.has(entry.id);
                  return (
                    <LogRow key={entry.id} entry={entry} isExpanded={isExpanded} onToggle={toggleExpand} />
                  );
                })}
                {!loading && rows.length === 0 && (
                  <tr>
                    <td colSpan={5} className="log-empty">
                      No log entries match the current filters.
                    </td>
                  </tr>
                )}
                {loading && (
                  <tr>
                    <td colSpan={5} className="log-empty">
                      Loading…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="log-pagination">
            <label className="log-page-size">
              <span className="text-muted">Rows per page</span>
              <select
                className="input"
                value={pageSize}
                onChange={(e) => handlePageSizeChange(Number(e.target.value))}
              >
                {PAGE_SIZE_OPTIONS.map((size) => (
                  <option key={size} value={size}>
                    {size}
                  </option>
                ))}
              </select>
            </label>
            <div className="log-pagination-nav">
              <button
                type="button"
                className="btn btn-secondary btn-small"
                disabled={currentPage <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Prev
              </button>
              <span className="text-muted">
                Page {currentPage + 1} of {totalPages}
              </span>
              <button
                type="button"
                className="btn btn-secondary btn-small"
                disabled={currentPage + 1 >= totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

interface LogRowProps {
  entry: LogEntry;
  isExpanded: boolean;
  onToggle: (id: number) => void;
}

function LogRow({ entry, isExpanded, onToggle }: LogRowProps) {
  return (
    <>
      <tr
        className={`log-row${isExpanded ? " expanded" : ""}`}
        onClick={() => onToggle(entry.id)}
        aria-expanded={isExpanded}
      >
        <td className="log-date">{new Date(entry.timestamp).toLocaleDateString()}</td>
        <td className="log-time">{new Date(entry.timestamp).toLocaleTimeString()}</td>
        <td>
          <span className={`level-chip level-${entry.level.toLowerCase()}`}>{entry.level}</span>
        </td>
        <td className="log-file">{entry.file ?? "—"}</td>
        <td className="log-message log-message-truncated">
          <span className="log-expand-chevron">▸</span>
          {entry.message}
        </td>
      </tr>
      {isExpanded && (
        <tr className="log-detail-row">
          <td colSpan={5}>
            <LogEntryDetail message={entry.message} />
          </td>
        </tr>
      )}
    </>
  );
}

function LogEntryDetail({ message }: { message: string }) {
  const parsed = useMemo(() => parseMessage(message), [message]);
  return (
    <div className="log-detail-panel">
      <div className="log-detail-header">
        <span className={`format-badge format-${parsed.format}`}>{parsed.label}</span>
      </div>
      {parsed.fields.length > 0 && (
        <div className="log-detail-fields">
          {parsed.fields.map((field, i) => (
            <div className="log-detail-field" key={`${field.key}-${i}`}>
              <span className="log-detail-field-key">{field.key}</span>
              <span className="log-detail-field-value">{field.value}</span>
            </div>
          ))}
        </div>
      )}
      <div>
        <div className="log-detail-message-label">Full message</div>
        <pre className="log-detail-message">{message}</pre>
      </div>
    </div>
  );
}

/** Renders a query-bar aggregation (SPL "stats count by" / LogQL count_over_time / rate) as a bar chart instead of the row table. */
function AggregationView({ aggregation }: { aggregation: LogAggregationResult }) {
  const isTimeSeries = aggregation.groupField === null;
  const items = aggregation.buckets.map((bucket) => {
    const value = bucket.rate ?? bucket.count;
    if (!isTimeSeries) {
      return { key: bucket.key, label: bucket.key, value, title: `${bucket.key}: ${bucket.count.toLocaleString()}` };
    }
    const when = new Date(bucket.key);
    const valueLabel = bucket.rate !== null ? `${bucket.rate.toFixed(2)}/s` : bucket.count.toLocaleString();
    return {
      key: bucket.key,
      label: when.toLocaleTimeString(),
      value,
      title: `${when.toLocaleString()}: ${valueLabel}`,
    };
  });

  return (
    <div className="log-aggregation">
      <div className="log-aggregation-summary text-muted">
        {aggregation.totalMatched.toLocaleString()} matched
        {aggregation.groupField ? ` · grouped by ${aggregation.groupField}` : " · over time"}
      </div>
      <BarChart items={items} emptyMessage="No results for this query." />
    </div>
  );
}

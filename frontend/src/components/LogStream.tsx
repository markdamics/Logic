import { useEffect, useMemo, useState } from "react";
import { fetchLogs } from "../api/client";
import type { LogLevel, LogQueryResult, LogSource } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("LogStream");

type SortColumn = "time" | "level" | "source";
type SortDirection = "asc" | "desc";

const LEVELS: LogLevel[] = ["ERROR", "WARN", "INFO", "DEBUG"];
const PAGE_SIZE = 10;
const SEARCH_DEBOUNCE_MS = 300;

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
}

export function LogStream({ sources, onCountChange }: LogStreamProps) {
  const [searchInput, setSearchInput] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [sourceFilter, setSourceFilter] = useState("");
  const [timeRange, setTimeRange] = useState("24h");
  const [severities, setSeverities] = useState<Set<LogLevel>>(new Set());
  const [sortColumn, setSortColumn] = useState<SortColumn>("time");
  const [sortDirection, setSortDirection] = useState<SortDirection>("desc");
  const [page, setPage] = useState(0);

  const [data, setData] = useState<LogQueryResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const sourceNames = useMemo(() => Array.from(new Set(sources.map((s) => s.name))).sort(), [sources]);
  const rangeMinutes = TIME_RANGES.find((r) => r.value === timeRange)?.minutes ?? 24 * 60;

  // Debounce free-text search so we don't hit the backend on every keystroke.
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(searchInput);
      setPage(0);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    if (sources.length === 0) {
      setData(null);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    const params = {
      search: debouncedSearch || undefined,
      levels: severities.size > 0 ? Array.from(severities) : undefined,
      source: sourceFilter || undefined,
      rangeMinutes,
      sortBy: sortColumn,
      sortDir: sortDirection,
      page,
      size: PAGE_SIZE,
    };
    logger.debug("Querying logs", params);

    fetchLogs(params)
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
  }, [sources.length, debouncedSearch, severities, sourceFilter, rangeMinutes, sortColumn, sortDirection, page]);

  useEffect(() => {
    onCountChange?.(data?.totalElements ?? 0);
  }, [data, onCountChange]);

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
        <input
          className="input log-search"
          placeholder="Search logs…"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />
        <select
          className="input"
          value={sourceFilter}
          onChange={(e) => {
            setSourceFilter(e.target.value);
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
          {LEVELS.map((level) => (
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

      <div className="log-table-wrapper">
        <table className="log-table">
          <thead>
            <tr>
              <th onClick={() => toggleSort("time")}>Time {sortArrow("time")}</th>
              <th onClick={() => toggleSort("level")}>Level {sortArrow("level")}</th>
              <th onClick={() => toggleSort("source")}>Source {sortArrow("source")}</th>
              <th>Message</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((entry) => (
              <tr key={entry.id}>
                <td className="log-time">{new Date(entry.timestamp).toLocaleTimeString()}</td>
                <td>
                  <span className={`level-chip level-${entry.level.toLowerCase()}`}>{entry.level}</span>
                </td>
                <td>{entry.source}</td>
                <td className="log-message">{entry.message}</td>
              </tr>
            ))}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={4} className="log-empty">
                  No log entries match the current filters.
                </td>
              </tr>
            )}
            {loading && (
              <tr>
                <td colSpan={4} className="log-empty">
                  Loading…
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="log-pagination">
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
  );
}

export type SourceType = "LOCAL_FILE" | "LOCAL_DIRECTORY" | "SFTP" | "HTTP";

export type SourceStatus = "UNVERIFIED" | "REACHABLE" | "UNREACHABLE";

export interface LogSource {
  id: number;
  name: string;
  type: SourceType;
  path: string | null;
  host: string | null;
  port: number | null;
  username: string | null;
  status: SourceStatus;
  enabled: boolean;
  live: boolean;
  changedFiles: string[];
  createdAt: string;
  lastCheckedAt: string | null;
}

export interface CreateSourceRequest {
  name: string;
  type: SourceType;
  path?: string;
  host?: string;
  port?: number;
  username?: string;
  password?: string;
}

export interface ConnectionTestResult {
  status: SourceStatus;
  message: string;
  checkedAt: string;
}

export type LogLevel = "ERROR" | "WARN" | "INFO" | "DEBUG";

export interface LogEntry {
  id: number;
  timestamp: string;
  level: LogLevel;
  source: string;
  file: string | null;
  message: string;
}

export interface LogAggregationBucket {
  /** The group's field value for "stats count by", or the bucket's start instant (ISO-8601) for a time-bucketed aggregation. */
  key: string;
  count: number;
  /** count/second — only set for a LogQL rate(...) query, null otherwise. */
  rate: number | null;
}

export interface LogAggregationResult {
  /** Null for a time-bucketed aggregation (count_over_time/rate) — buckets are chronological, not grouped by field. */
  groupField: string | null;
  buckets: LogAggregationBucket[];
  totalMatched: number;
}

export interface LogQueryResult {
  content: LogEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  /** Set instead of content when the query ended in an aggregation stage (SPL "| stats count by", LogQL count_over_time/rate). */
  aggregation: LogAggregationResult | null;
}

export interface LogQueryParams {
  search?: string;
  levels?: LogLevel[];
  source?: string;
  file?: string;
  rangeMinutes?: number;
  sortBy?: "time" | "level" | "source" | "file";
  sortDir?: "asc" | "desc";
  page?: number;
  size?: number;
}

export type QueryLanguage = "LUCENE" | "SPL" | "LOGQL";

export interface LogQueryLanguageParams {
  q: string;
  queryLanguage: QueryLanguage;
  source?: string;
  file?: string;
  rangeMinutes?: number;
  sortBy?: "time" | "level" | "source" | "file";
  sortDir?: "asc" | "desc";
  page?: number;
  size?: number;
}

/** Adds SIMPLE (a snapshot of the plain-filter UI) to the query-bar languages, for SavedSearch only. */
export type SavedSearchLanguage = QueryLanguage | "SIMPLE";

export interface SavedSearch {
  id: number;
  name: string;
  queryLanguage: SavedSearchLanguage;
  query: string | null;
  search: string | null;
  levels: LogLevel[];
  source: string | null;
  file: string | null;
  rangeMinutes: number;
  sortBy: string;
  sortDir: string;
  createdAt: string;
}

export interface CreateSavedSearchRequest {
  name: string;
  queryLanguage: SavedSearchLanguage;
  query?: string;
  search?: string;
  levels?: LogLevel[];
  source?: string;
  file?: string;
  rangeMinutes: number;
  sortBy: string;
  sortDir: string;
}

export type AlertRuleType = "THRESHOLD" | "ANOMALY";
export type AlertMetric = "COUNT" | "RATE";
export type ComparisonOperator = "GT" | "GTE";

export interface AlertRule {
  id: number;
  name: string;
  queryLanguage: SavedSearchLanguage;
  query: string | null;
  search: string | null;
  levels: LogLevel[];
  source: string | null;
  file: string | null;
  ruleType: AlertRuleType;
  windowMinutes: number;
  metric: AlertMetric;
  comparisonOp: ComparisonOperator | null;
  threshold: number | null;
  anomalyBaselineWindows: number | null;
  anomalyStdDevMultiplier: number | null;
  muted: boolean;
  lastTriggeredAt: string | null;
  lastEvaluatedAt: string | null;
  webhookUrl: string | null;
  webhookSecretConfigured: boolean;
  createdAt: string;
}

export interface CreateAlertRuleRequest {
  name: string;
  queryLanguage: SavedSearchLanguage;
  query?: string;
  search?: string;
  levels?: LogLevel[];
  source?: string;
  file?: string;
  ruleType: AlertRuleType;
  windowMinutes: number;
  metric: AlertMetric;
  comparisonOp?: ComparisonOperator;
  threshold?: number;
  anomalyBaselineWindows?: number;
  anomalyStdDevMultiplier?: number;
  webhookUrl?: string;
  webhookSecret?: string;
}

export interface AlertEvent {
  id: number;
  triggeredAt: string;
  resolvedAt: string | null;
  metricValue: number;
  thresholdAtTrigger: number | null;
  webhookStatus: number | null;
}

export interface SourceActivity {
  source: string;
  status: SourceStatus;
  enabled: boolean;
  live: boolean;
  entriesLast24h: number;
  errorsLast24h: number;
}

export interface FileActivity {
  file: string;
  source: string;
  entriesLast24h: number;
  errorsLast24h: number;
}

export interface DashboardSummary {
  totalSources: number;
  reachableSources: number;
  unreachableSources: number;
  unverifiedSources: number;
  enabledSources: number;
  disabledSources: number;
  entriesLast24h: number;
  errorsLast24h: number;
  warningsLast24h: number;
  recentIssues: LogEntry[];
  sourceActivity: SourceActivity[];
  fileActivity: FileActivity[];
}

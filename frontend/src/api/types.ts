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

export interface LogQueryResult {
  content: LogEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
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

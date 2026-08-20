import type {
  AlertEvent,
  AlertRule,
  AppConfig,
  ConnectionTestResult,
  CreateAlertRuleRequest,
  CreateSavedSearchRequest,
  CreateSourceRequest,
  DashboardSummary,
  LogQueryLanguageParams,
  LogQueryParams,
  LogQueryResult,
  LogSource,
  SavedSearch,
} from "./types";
import { createLogger } from "../utils/logger";

const logger = createLogger("api");

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = init?.method ?? "GET";
  logger.debug(`${method} ${path}`);

  const response = await fetch(`/api${path}`, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });

  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const body = await response.json();
      if (body?.message) message = body.message;
    } catch {
      // response had no JSON body - keep default message
    }
    logger.warn(`${method} ${path} failed`, { status: response.status, message });
    throw new ApiError(message, response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function listSources(): Promise<LogSource[]> {
  return request<LogSource[]>("/sources");
}

export function createSource(req: CreateSourceRequest): Promise<LogSource> {
  return request<LogSource>("/sources", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function updateSource(id: number, req: CreateSourceRequest): Promise<LogSource> {
  return request<LogSource>(`/sources/${id}`, {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

export function deleteSource(id: number): Promise<void> {
  return request<void>(`/sources/${id}`, { method: "DELETE" });
}

export function testConnection(id: number): Promise<ConnectionTestResult> {
  return request<ConnectionTestResult>(`/sources/${id}/test-connection`, {
    method: "POST",
  });
}

export function setSourceEnabled(id: number, enabled: boolean): Promise<LogSource> {
  return request<LogSource>(`/sources/${id}/${enabled ? "enable" : "disable"}`, {
    method: "POST",
  });
}

export function setSourceLive(id: number, live: boolean): Promise<LogSource> {
  return request<LogSource>(`/sources/${id}/${live ? "enable-live" : "disable-live"}`, {
    method: "POST",
  });
}

export function fetchLogs(params: LogQueryParams): Promise<LogQueryResult> {
  const query = new URLSearchParams();
  if (params.search) query.set("search", params.search);
  if (params.levels && params.levels.length > 0) query.set("level", params.levels.join(","));
  if (params.source) query.set("source", params.source);
  if (params.file) query.set("file", params.file);
  if (params.rangeMinutes !== undefined) query.set("rangeMinutes", String(params.rangeMinutes));
  if (params.sortBy) query.set("sortBy", params.sortBy);
  if (params.sortDir) query.set("sortDir", params.sortDir);
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));

  return request<LogQueryResult>(`/logs?${query.toString()}`);
}

export function queryLogs(params: LogQueryLanguageParams): Promise<LogQueryResult> {
  const query = new URLSearchParams();
  query.set("q", params.q);
  query.set("queryLanguage", params.queryLanguage);
  if (params.source) query.set("source", params.source);
  if (params.file) query.set("file", params.file);
  if (params.rangeMinutes !== undefined) query.set("rangeMinutes", String(params.rangeMinutes));
  if (params.sortBy) query.set("sortBy", params.sortBy);
  if (params.sortDir) query.set("sortDir", params.sortDir);
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));

  return request<LogQueryResult>(`/logs/query?${query.toString()}`);
}

export function listLogFiles(source?: string): Promise<string[]> {
  const query = new URLSearchParams();
  if (source) query.set("source", source);
  return request<string[]>(`/logs/files?${query.toString()}`);
}

export function fetchDashboardSummary(): Promise<DashboardSummary> {
  return request<DashboardSummary>("/dashboard/summary");
}

export function reloadLogs(): Promise<void> {
  return request<void>("/logs/reload", { method: "POST" });
}

export function listSavedSearches(): Promise<SavedSearch[]> {
  return request<SavedSearch[]>("/saved-searches");
}

export function createSavedSearch(req: CreateSavedSearchRequest): Promise<SavedSearch> {
  return request<SavedSearch>("/saved-searches", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function deleteSavedSearch(id: number): Promise<void> {
  return request<void>(`/saved-searches/${id}`, { method: "DELETE" });
}

export function runSavedSearch(id: number, page = 0, size = 10): Promise<LogQueryResult> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return request<LogQueryResult>(`/saved-searches/${id}/run?${query.toString()}`, { method: "POST" });
}

export function listAlertRules(): Promise<AlertRule[]> {
  return request<AlertRule[]>("/alerts/rules");
}

export function createAlertRule(req: CreateAlertRuleRequest): Promise<AlertRule> {
  return request<AlertRule>("/alerts/rules", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function updateAlertRule(id: number, req: CreateAlertRuleRequest): Promise<AlertRule> {
  return request<AlertRule>(`/alerts/rules/${id}`, {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

export function deleteAlertRule(id: number): Promise<void> {
  return request<void>(`/alerts/rules/${id}`, { method: "DELETE" });
}

export function setAlertRuleMuted(id: number, muted: boolean): Promise<AlertRule> {
  return request<AlertRule>(`/alerts/rules/${id}/${muted ? "mute" : "unmute"}`, { method: "POST" });
}

export function listAlertEvents(id: number): Promise<AlertEvent[]> {
  return request<AlertEvent[]>(`/alerts/rules/${id}/events`);
}

export function testAlertWebhook(id: number): Promise<void> {
  return request<void>(`/alerts/rules/${id}/test-webhook`, { method: "POST" });
}

export function getAppConfig(): Promise<AppConfig> {
  return request<AppConfig>("/config");
}

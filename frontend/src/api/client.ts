import type { ConnectionTestResult, CreateSourceRequest, LogQueryParams, LogQueryResult, LogSource } from "./types";
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

export function fetchLogs(params: LogQueryParams): Promise<LogQueryResult> {
  const query = new URLSearchParams();
  if (params.search) query.set("search", params.search);
  if (params.levels && params.levels.length > 0) query.set("level", params.levels.join(","));
  if (params.source) query.set("source", params.source);
  if (params.rangeMinutes !== undefined) query.set("rangeMinutes", String(params.rangeMinutes));
  if (params.sortBy) query.set("sortBy", params.sortBy);
  if (params.sortDir) query.set("sortDir", params.sortDir);
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));

  return request<LogQueryResult>(`/logs?${query.toString()}`);
}

import type { ConnectionTestResult, CreateSourceRequest, LogSource } from "./types";

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
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

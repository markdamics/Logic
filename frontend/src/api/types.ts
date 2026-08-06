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

import { useCallback, useEffect, useState } from "react";
import { listAuditLog } from "../api/client";
import type { AuditLogEntry } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("useAuditLog");

export function useAuditLog() {
  const [auditLog, setAuditLog] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAuditLog();
      setAuditLog(data);
      logger.debug(`Loaded ${data.length} audit log entr${data.length === 1 ? "y" : "ies"}`);
    } catch (e) {
      logger.error("Failed to load audit log", e);
      setError(e instanceof Error ? e.message : "Failed to load audit log");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { auditLog, loading, error, refresh };
}

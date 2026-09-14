import { useCallback, useEffect, useState } from "react";
import { listTemplates } from "../api/client";
import type { LogTemplate, TemplateSort } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("usePatterns");

export function usePatterns(source: string | undefined, sort: TemplateSort) {
  const [templates, setTemplates] = useState<LogTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listTemplates(source, sort);
      setTemplates(data);
      logger.debug(`Loaded ${data.length} template(s)`);
    } catch (e) {
      logger.error("Failed to load templates", e);
      setError(e instanceof Error ? e.message : "Failed to load templates");
    } finally {
      setLoading(false);
    }
  }, [source, sort]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { templates, loading, error, refresh };
}

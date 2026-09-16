import { useCallback, useEffect, useState } from "react";
import { deleteTemplate, listTemplates, splitTemplate } from "../api/client";
import type { LogTemplate, TemplateSort } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("usePatterns");

export function usePatterns(source: string | undefined, file: string | undefined, sort: TemplateSort) {
  const [templates, setTemplates] = useState<LogTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listTemplates(source, file, sort);
      setTemplates(data);
      logger.debug(`Loaded ${data.length} template(s)`);
    } catch (e) {
      logger.error("Failed to load templates", e);
      setError(e instanceof Error ? e.message : "Failed to load templates");
    } finally {
      setLoading(false);
    }
  }, [source, file, sort]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Refetch rather than splice the local list in place - a delete/split changes which rows
  // exist and a split's new rows need to land in their correct sort position, which only the
  // server-side sort (see listTemplates' sort param) actually knows how to compute.
  const remove = useCallback(
    async (id: number) => {
      await deleteTemplate(id);
      logger.info(`Deleted template ${id}`);
      await refresh();
    },
    [refresh],
  );

  const split = useCallback(
    async (id: number) => {
      const created = await splitTemplate(id);
      logger.info(`Split template ${id} into ${created.length} templates`);
      await refresh();
    },
    [refresh],
  );

  return { templates, loading, error, refresh, remove, split };
}

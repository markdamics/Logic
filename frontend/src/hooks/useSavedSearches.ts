import { useCallback, useEffect, useState } from "react";
import { createSavedSearch, deleteSavedSearch, listSavedSearches, runSavedSearch } from "../api/client";
import type { CreateSavedSearchRequest, LogQueryResult, SavedSearch } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("useSavedSearches");

export function useSavedSearches() {
  const [savedSearches, setSavedSearches] = useState<SavedSearch[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listSavedSearches();
      setSavedSearches(data);
      logger.debug(`Loaded ${data.length} saved search(es)`);
    } catch (e) {
      logger.error("Failed to load saved searches", e);
      setError(e instanceof Error ? e.message : "Failed to load saved searches");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const create = useCallback(async (req: CreateSavedSearchRequest) => {
    const created = await createSavedSearch(req);
    logger.info(`Created saved search '${created.name}' (id=${created.id})`);
    setSavedSearches((prev) => [...prev, created]);
    return created;
  }, []);

  const remove = useCallback(async (id: number) => {
    await deleteSavedSearch(id);
    logger.info(`Deleted saved search ${id}`);
    setSavedSearches((prev) => prev.filter((s) => s.id !== id));
  }, []);

  const run = useCallback(async (id: number, page = 0, size = 10): Promise<LogQueryResult> => {
    logger.info(`Running saved search ${id}`);
    return runSavedSearch(id, page, size);
  }, []);

  return { savedSearches, loading, error, refresh, create, remove, run };
}

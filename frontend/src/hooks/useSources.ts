import { useCallback, useEffect, useState } from "react";
import { createSource, deleteSource, listSources, setSourceEnabled, setSourceLive, testConnection, updateSource, uploadSource } from "../api/client";
import type { CreateSourceRequest, LogSource } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("useSources");

// How often to quietly re-poll the source list so the "new data available"
// indicator (changedFiles) stays fresh for non-live sources without the user
// having to navigate away and back.
const UPDATE_POLL_INTERVAL_MS = 5000;

export function useSources() {
  const [sources, setSources] = useState<LogSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listSources();
      setSources(data);
      logger.debug(`Loaded ${data.length} source(s)`);
    } catch (e) {
      logger.error("Failed to load sources", e);
      setError(e instanceof Error ? e.message : "Failed to load sources");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const hasNonLiveEnabledSource = sources.some((s) => s.enabled && !s.live);

  // Quiet background poll (no loading flicker) so changedFiles badges stay current.
  useEffect(() => {
    if (!hasNonLiveEnabledSource) {
      return;
    }
    const timer = setInterval(() => {
      listSources()
        .then((data) => setSources(data))
        .catch((e) => logger.warn("Background source refresh failed", e));
    }, UPDATE_POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [hasNonLiveEnabledSource]);

  const create = useCallback(async (req: CreateSourceRequest) => {
    const created = await createSource(req);
    logger.info(`Created source '${created.name}' (id=${created.id})`);
    setSources((prev) => [...prev, created]);
  }, []);

  const update = useCallback(async (id: number, req: CreateSourceRequest) => {
    const updated = await updateSource(id, req);
    logger.info(`Updated source ${id} -> '${updated.name}'`);
    setSources((prev) => prev.map((s) => (s.id === id ? updated : s)));
  }, []);

  const upload = useCallback(async (formData: FormData) => {
    const created = await uploadSource(formData);
    logger.info(`Uploaded source '${created.name}' (id=${created.id})`);
    setSources((prev) => [...prev, created]);
  }, []);

  const remove = useCallback(async (id: number) => {
    await deleteSource(id);
    logger.info(`Deleted source ${id}`);
    setSources((prev) => prev.filter((s) => s.id !== id));
  }, []);

  const check = useCallback(async (id: number) => {
    const result = await testConnection(id);
    logger.info(`Tested source ${id} -> ${result.status}`);
    setSources((prev) =>
      prev.map((s) =>
        s.id === id ? { ...s, status: result.status, lastCheckedAt: result.checkedAt } : s,
      ),
    );
    return result;
  }, []);

  const toggleEnabled = useCallback(async (id: number, enabled: boolean) => {
    const updated = await setSourceEnabled(id, enabled);
    logger.info(`${enabled ? "Enabled" : "Disabled"} source ${id}`);
    setSources((prev) => prev.map((s) => (s.id === id ? updated : s)));
  }, []);

  const toggleLive = useCallback(async (id: number, live: boolean) => {
    const updated = await setSourceLive(id, live);
    logger.info(`Marked source ${id} as ${live ? "live" : "not live"}`);
    setSources((prev) => prev.map((s) => (s.id === id ? updated : s)));
  }, []);

  return { sources, loading, error, refresh, create, update, upload, remove, check, toggleEnabled, toggleLive };
}

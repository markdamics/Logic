import { useCallback, useEffect, useState } from "react";
import { createSource, deleteSource, listSources, testConnection, updateSource } from "../api/client";
import type { CreateSourceRequest, LogSource } from "../api/types";

export function useSources() {
  const [sources, setSources] = useState<LogSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSources(await listSources());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load sources");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const create = useCallback(
    async (req: CreateSourceRequest) => {
      const created = await createSource(req);
      setSources((prev) => [...prev, created]);
    },
    [],
  );

  const update = useCallback(async (id: number, req: CreateSourceRequest) => {
    const updated = await updateSource(id, req);
    setSources((prev) => prev.map((s) => (s.id === id ? updated : s)));
  }, []);

  const remove = useCallback(async (id: number) => {
    await deleteSource(id);
    setSources((prev) => prev.filter((s) => s.id !== id));
  }, []);

  const check = useCallback(async (id: number) => {
    const result = await testConnection(id);
    setSources((prev) =>
      prev.map((s) =>
        s.id === id ? { ...s, status: result.status, lastCheckedAt: result.checkedAt } : s,
      ),
    );
    return result;
  }, []);

  return { sources, loading, error, refresh, create, update, remove, check };
}

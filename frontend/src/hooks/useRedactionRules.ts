import { useCallback, useEffect, useState } from "react";
import {
  createRedactionRule,
  deleteRedactionRule,
  listRedactionRules,
  updateRedactionRule,
} from "../api/client";
import type { CreateRedactionRuleRequest, RedactionRule } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("useRedactionRules");

export function useRedactionRules() {
  const [redactionRules, setRedactionRules] = useState<RedactionRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listRedactionRules();
      setRedactionRules(data);
      logger.debug(`Loaded ${data.length} redaction rule(s)`);
    } catch (e) {
      logger.error("Failed to load redaction rules", e);
      setError(e instanceof Error ? e.message : "Failed to load redaction rules");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const create = useCallback(async (req: CreateRedactionRuleRequest) => {
    const created = await createRedactionRule(req);
    logger.info(`Created redaction rule '${created.name}' (id=${created.id})`);
    setRedactionRules((prev) => [...prev, created]);
    return created;
  }, []);

  const update = useCallback(async (id: number, req: CreateRedactionRuleRequest) => {
    const updated = await updateRedactionRule(id, req);
    logger.info(`Updated redaction rule ${id} -> '${updated.name}'`);
    setRedactionRules((prev) => prev.map((r) => (r.id === id ? updated : r)));
    return updated;
  }, []);

  const remove = useCallback(async (id: number) => {
    await deleteRedactionRule(id);
    logger.info(`Deleted redaction rule ${id}`);
    setRedactionRules((prev) => prev.filter((r) => r.id !== id));
  }, []);

  return { redactionRules, loading, error, refresh, create, update, remove };
}

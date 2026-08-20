import { useCallback, useEffect, useState } from "react";
import {
  createAlertRule,
  deleteAlertRule,
  listAlertEvents,
  listAlertRules,
  setAlertRuleMuted,
  testAlertWebhook,
  updateAlertRule,
} from "../api/client";
import type { AlertEvent, AlertRule, CreateAlertRuleRequest } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("useAlertRules");

export function useAlertRules() {
  const [alertRules, setAlertRules] = useState<AlertRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAlertRules();
      setAlertRules(data);
      logger.debug(`Loaded ${data.length} alert rule(s)`);
    } catch (e) {
      logger.error("Failed to load alert rules", e);
      setError(e instanceof Error ? e.message : "Failed to load alert rules");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const create = useCallback(async (req: CreateAlertRuleRequest) => {
    const created = await createAlertRule(req);
    logger.info(`Created alert rule '${created.name}' (id=${created.id})`);
    setAlertRules((prev) => [...prev, created]);
    return created;
  }, []);

  const update = useCallback(async (id: number, req: CreateAlertRuleRequest) => {
    const updated = await updateAlertRule(id, req);
    logger.info(`Updated alert rule ${id} -> '${updated.name}'`);
    setAlertRules((prev) => prev.map((r) => (r.id === id ? updated : r)));
    return updated;
  }, []);

  const remove = useCallback(async (id: number) => {
    await deleteAlertRule(id);
    logger.info(`Deleted alert rule ${id}`);
    setAlertRules((prev) => prev.filter((r) => r.id !== id));
  }, []);

  const toggleMuted = useCallback(async (id: number, muted: boolean) => {
    const updated = await setAlertRuleMuted(id, muted);
    logger.info(`${muted ? "Muted" : "Unmuted"} alert rule ${id}`);
    setAlertRules((prev) => prev.map((r) => (r.id === id ? updated : r)));
  }, []);

  const events = useCallback(async (id: number): Promise<AlertEvent[]> => {
    return listAlertEvents(id);
  }, []);

  const testWebhook = useCallback(async (id: number) => {
    await testAlertWebhook(id);
    logger.info(`Sent a test webhook for alert rule ${id}`);
  }, []);

  return { alertRules, loading, error, refresh, create, update, remove, toggleMuted, events, testWebhook };
}

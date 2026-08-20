import { useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import { testAlertWebhook } from "../api/client";
import type {
  AlertMetric,
  AlertRule,
  AlertRuleType,
  ComparisonOperator,
  CreateAlertRuleRequest,
  LogLevel,
  QueryLanguage,
  SavedSearchLanguage,
} from "../api/types";
import { ApiError } from "../api/client";
import { createLogger } from "../utils/logger";

const logger = createLogger("AlertRuleDialog");

const QUERY_LANGUAGES: { value: QueryLanguage; label: string; placeholder: string }[] = [
  { value: "LUCENE", label: "Lucene", placeholder: "level:ERROR AND source:payments-api" },
  { value: "SPL", label: "SPL", placeholder: "level=ERROR AND source=payments-api" },
  { value: "LOGQL", label: "LogQL", placeholder: '{level="ERROR"} |= "timeout"' },
];

const LEVELS: LogLevel[] = ["ERROR", "WARN", "INFO", "DEBUG"];

interface AlertRuleDialogProps {
  rule?: AlertRule;
  sourceNames: string[];
  onClose: () => void;
  onSubmit: (req: CreateAlertRuleRequest) => Promise<unknown>;
}

export function AlertRuleDialog({ rule, sourceNames, onClose, onSubmit }: AlertRuleDialogProps) {
  const isEdit = rule !== undefined;
  const [name, setName] = useState(rule?.name ?? "");
  const [mode, setMode] = useState<"simple" | "query">(
    rule && rule.queryLanguage !== "SIMPLE" ? "query" : "simple",
  );
  const [queryLanguage, setQueryLanguage] = useState<QueryLanguage>(
    rule && rule.queryLanguage !== "SIMPLE" ? rule.queryLanguage : "LUCENE",
  );
  const [query, setQuery] = useState(rule?.query ?? "");
  const [search, setSearch] = useState(rule?.search ?? "");
  const [levels, setLevels] = useState<Set<LogLevel>>(new Set(rule?.levels ?? []));
  const [source, setSource] = useState(rule?.source ?? "");
  const [file, setFile] = useState(rule?.file ?? "");
  const [ruleType, setRuleType] = useState<AlertRuleType>(rule?.ruleType ?? "THRESHOLD");
  const [windowMinutes, setWindowMinutes] = useState(rule ? String(rule.windowMinutes) : "5");
  const [metric, setMetric] = useState<AlertMetric>(rule?.metric ?? "COUNT");
  const [comparisonOp, setComparisonOp] = useState<ComparisonOperator>(rule?.comparisonOp ?? "GT");
  const [threshold, setThreshold] = useState(rule?.threshold != null ? String(rule.threshold) : "5");
  const [baselineWindows, setBaselineWindows] = useState(
    rule?.anomalyBaselineWindows != null ? String(rule.anomalyBaselineWindows) : "6",
  );
  const [stdDevMultiplier, setStdDevMultiplier] = useState(
    rule?.anomalyStdDevMultiplier != null ? String(rule.anomalyStdDevMultiplier) : "3",
  );
  const [webhookUrl, setWebhookUrl] = useState(rule?.webhookUrl ?? "");
  const [webhookSecret, setWebhookSecret] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [testStatus, setTestStatus] = useState<string | null>(null);

  const toggleLevel = (level: LogLevel) => {
    setLevels((prev) => {
      const next = new Set(prev);
      if (next.has(level)) next.delete(level);
      else next.add(level);
      return next;
    });
  };

  const handleTestWebhook = async () => {
    if (!rule) return;
    setTestStatus("Sending…");
    try {
      await testAlertWebhook(rule.id);
      setTestStatus("Test webhook sent.");
    } catch (e) {
      setTestStatus(e instanceof ApiError ? e.message : "Failed to send test webhook");
    }
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const queryLanguageValue: SavedSearchLanguage = mode === "simple" ? "SIMPLE" : queryLanguage;
      const req: CreateAlertRuleRequest = {
        name,
        queryLanguage: queryLanguageValue,
        query: mode === "query" ? query : undefined,
        search: mode === "simple" ? search || undefined : undefined,
        levels: mode === "simple" && levels.size > 0 ? Array.from(levels) : undefined,
        source: source || undefined,
        file: file || undefined,
        ruleType,
        windowMinutes: Number(windowMinutes),
        metric,
        comparisonOp: ruleType === "THRESHOLD" ? comparisonOp : undefined,
        threshold: ruleType === "THRESHOLD" ? Number(threshold) : undefined,
        anomalyBaselineWindows: ruleType === "ANOMALY" ? Number(baselineWindows) : undefined,
        anomalyStdDevMultiplier: ruleType === "ANOMALY" ? Number(stdDevMultiplier) : undefined,
        webhookUrl: webhookUrl || undefined,
        webhookSecret: webhookSecret || undefined,
      };
      await onSubmit(req);
      onClose();
    } catch (e) {
      logger.warn(`Failed to ${isEdit ? "update" : "arm"} alert rule`, e);
      setError(e instanceof ApiError ? e.message : `Failed to ${isEdit ? "update" : "arm"} alert rule`);
    } finally {
      setSubmitting(false);
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <h2>{isEdit ? "Edit alert rule" : "Add new rule"}</h2>
        {error && <div className="error-banner">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label htmlFor="alert-name">Name</label>
            <input
              id="alert-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              placeholder="e.g. payment-worker error spike"
            />
          </div>

          <div className="form-field">
            <label>Scope</label>
            <div className="log-mode-toggle">
              <button type="button" className={`log-mode-btn${mode === "simple" ? " active" : ""}`} onClick={() => setMode("simple")}>
                Simple
              </button>
              <button type="button" className={`log-mode-btn${mode === "query" ? " active" : ""}`} onClick={() => setMode("query")}>
                Query
              </button>
            </div>
          </div>

          {mode === "simple" ? (
            <>
              <div className="form-field">
                <label htmlFor="alert-search">Search text (optional)</label>
                <input id="alert-search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="substring" />
              </div>
              <div className="form-field">
                <label>Severity (any level if none selected)</label>
                <div className="log-severity-chips">
                  {LEVELS.map((level) => (
                    <button
                      key={level}
                      type="button"
                      className={`chip-toggle level-${level.toLowerCase()}${levels.has(level) ? " active" : ""}`}
                      onClick={() => toggleLevel(level)}
                    >
                      {level}
                    </button>
                  ))}
                </div>
              </div>
            </>
          ) : (
            <>
              <div className="form-field">
                <label htmlFor="alert-language">Language</label>
                <select id="alert-language" value={queryLanguage} onChange={(e) => setQueryLanguage(e.target.value as QueryLanguage)}>
                  {QUERY_LANGUAGES.map((lang) => (
                    <option key={lang.value} value={lang.value}>
                      {lang.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="form-field">
                <label htmlFor="alert-query">Query</label>
                <input
                  id="alert-query"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={QUERY_LANGUAGES.find((l) => l.value === queryLanguage)?.placeholder}
                  required
                />
              </div>
            </>
          )}

          <div className="form-row">
            <div className="form-field">
              <label htmlFor="alert-source">Source (optional)</label>
              <input id="alert-source" list="alert-source-options" value={source} onChange={(e) => setSource(e.target.value)} />
              <datalist id="alert-source-options">
                {sourceNames.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
            </div>
            <div className="form-field">
              <label htmlFor="alert-file">File (optional)</label>
              <input id="alert-file" value={file} onChange={(e) => setFile(e.target.value)} />
            </div>
          </div>

          <div className="form-row">
            <div className="form-field">
              <label htmlFor="alert-type">Rule type</label>
              <select id="alert-type" value={ruleType} onChange={(e) => setRuleType(e.target.value as AlertRuleType)}>
                <option value="THRESHOLD">Threshold / pattern</option>
                <option value="ANOMALY">Anomaly (statistical baseline)</option>
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="alert-metric">Metric</label>
              <select id="alert-metric" value={metric} onChange={(e) => setMetric(e.target.value as AlertMetric)}>
                <option value="COUNT">Count</option>
                <option value="RATE">Rate (count/sec)</option>
              </select>
            </div>
            <div className="form-field" style={{ maxWidth: 140 }}>
              <label htmlFor="alert-window">Window (min)</label>
              <input
                id="alert-window"
                type="number"
                min={1}
                value={windowMinutes}
                onChange={(e) => setWindowMinutes(e.target.value)}
                required
              />
            </div>
          </div>

          {ruleType === "THRESHOLD" ? (
            <div className="form-row">
              <div className="form-field" style={{ maxWidth: 140 }}>
                <label htmlFor="alert-comparison">Comparison</label>
                <select id="alert-comparison" value={comparisonOp} onChange={(e) => setComparisonOp(e.target.value as ComparisonOperator)}>
                  <option value="GT">{"> greater than"}</option>
                  <option value="GTE">{"≥ greater or equal"}</option>
                </select>
              </div>
              <div className="form-field">
                <label htmlFor="alert-threshold">Threshold</label>
                <input id="alert-threshold" type="number" step="any" value={threshold} onChange={(e) => setThreshold(e.target.value)} required />
              </div>
            </div>
          ) : (
            <div className="form-row">
              <div className="form-field">
                <label htmlFor="alert-baseline">Baseline windows</label>
                <input
                  id="alert-baseline"
                  type="number"
                  min={1}
                  value={baselineWindows}
                  onChange={(e) => setBaselineWindows(e.target.value)}
                  required
                />
              </div>
              <div className="form-field">
                <label htmlFor="alert-stddev">Std-dev multiplier</label>
                <input
                  id="alert-stddev"
                  type="number"
                  step="any"
                  min={0.01}
                  value={stdDevMultiplier}
                  onChange={(e) => setStdDevMultiplier(e.target.value)}
                  required
                />
              </div>
            </div>
          )}

          <div className="form-field">
            <label htmlFor="alert-webhook-url">Webhook URL (optional)</label>
            <input
              id="alert-webhook-url"
              value={webhookUrl}
              onChange={(e) => setWebhookUrl(e.target.value)}
              placeholder="https://your-automation-platform/hook"
            />
          </div>
          <div className="form-row">
            <div className="form-field">
              <label htmlFor="alert-webhook-secret">Webhook secret (optional)</label>
              <input
                id="alert-webhook-secret"
                type="password"
                value={webhookSecret}
                onChange={(e) => setWebhookSecret(e.target.value)}
                placeholder={rule?.webhookSecretConfigured ? "Leave blank to keep current secret" : "Signs the payload (X-Logic-Signature)"}
              />
            </div>
            {isEdit && rule?.webhookUrl && (
              <div className="form-field" style={{ justifyContent: "flex-end" }}>
                <button type="button" className="btn btn-secondary btn-small" onClick={handleTestWebhook}>
                  Send test
                </button>
              </div>
            )}
          </div>
          {testStatus && <div className="text-muted">{testStatus}</div>}

          <div className="form-actions">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? (isEdit ? "Saving…" : "Adding…") : isEdit ? "Save" : "Add rule"}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  );
}

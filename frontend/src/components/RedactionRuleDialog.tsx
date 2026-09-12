import { useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import { ApiError } from "../api/client";
import type { CreateRedactionRuleRequest, RedactionRule } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("RedactionRuleDialog");

interface RedactionRuleDialogProps {
  rule?: RedactionRule;
  sourceNames: string[];
  onClose: () => void;
  onSubmit: (req: CreateRedactionRuleRequest) => Promise<unknown>;
}

export function RedactionRuleDialog({ rule, sourceNames, onClose, onSubmit }: RedactionRuleDialogProps) {
  const isEdit = rule !== undefined;
  const [name, setName] = useState(rule?.name ?? "");
  const [pattern, setPattern] = useState(rule?.pattern ?? "");
  const [replacement, setReplacement] = useState(rule?.replacement ?? "");
  const [source, setSource] = useState(rule?.source ?? "");
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const req: CreateRedactionRuleRequest = {
        name,
        pattern,
        replacement: replacement || undefined,
        source: source || undefined,
        enabled,
      };
      await onSubmit(req);
      onClose();
    } catch (e) {
      logger.warn(`Failed to ${isEdit ? "update" : "add"} redaction rule`, e);
      setError(e instanceof ApiError ? e.message : `Failed to ${isEdit ? "update" : "add"} redaction rule`);
    } finally {
      setSubmitting(false);
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <h2>{isEdit ? "Edit redaction rule" : "Add redaction rule"}</h2>
        {error && <div className="error-banner">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label htmlFor="redaction-name">Name</label>
            <input
              id="redaction-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              placeholder="e.g. mask emails"
            />
          </div>

          <div className="form-field">
            <label htmlFor="redaction-pattern">Regex pattern</label>
            <input
              id="redaction-pattern"
              value={pattern}
              onChange={(e) => setPattern(e.target.value)}
              required
              placeholder="[\w.+-]+@[\w-]+\.[\w.-]+"
            />
          </div>

          <div className="form-row">
            <div className="form-field">
              <label htmlFor="redaction-replacement">Mask text (optional)</label>
              <input
                id="redaction-replacement"
                value={replacement}
                onChange={(e) => setReplacement(e.target.value)}
                placeholder="*** (default)"
              />
            </div>
            <div className="form-field">
              <label htmlFor="redaction-source">Source (optional, blank = global)</label>
              <input
                id="redaction-source"
                list="redaction-source-options"
                value={source}
                onChange={(e) => setSource(e.target.value)}
              />
              <datalist id="redaction-source-options">
                {sourceNames.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
            </div>
          </div>

          <div className="form-field">
            <label>
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Enabled
            </label>
          </div>

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

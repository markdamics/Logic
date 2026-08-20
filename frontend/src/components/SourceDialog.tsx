import { useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import type { CreateSourceRequest, LogSource, SourceType } from "../api/types";
import { ApiError } from "../api/client";
import { createLogger } from "../utils/logger";
import { SourceBrowseDialog } from "./SourceBrowseDialog";

const logger = createLogger("SourceDialog");

interface SourceDialogProps {
  source?: LogSource;
  onClose: () => void;
  onSubmit: (req: CreateSourceRequest) => Promise<unknown>;
}

const TYPE_OPTIONS: { value: SourceType; label: string }[] = [
  { value: "LOCAL_FILE", label: "Local file" },
  { value: "LOCAL_DIRECTORY", label: "Local directory" },
  { value: "SFTP", label: "SFTP server" },
  { value: "HTTP", label: "HTTP(S) URL" },
];

export function SourceDialog({ source, onClose, onSubmit }: SourceDialogProps) {
  const isEdit = source !== undefined;
  const [name, setName] = useState(source?.name ?? "");
  const [type, setType] = useState<SourceType>(source?.type ?? "LOCAL_FILE");
  const [path, setPath] = useState(source?.path ?? "");
  const [host, setHost] = useState(source?.host ?? "");
  const [port, setPort] = useState(source?.port ? String(source.port) : "22");
  const [username, setUsername] = useState(source?.username ?? "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [browsing, setBrowsing] = useState(false);
  const isLocal = type === "LOCAL_FILE" || type === "LOCAL_DIRECTORY";

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const req: CreateSourceRequest = { name, type, path: path || undefined };
      if (type === "SFTP") {
        req.host = host || undefined;
        req.port = port ? Number(port) : undefined;
        req.username = username || undefined;
        req.password = password || undefined;
      }
      await onSubmit(req);
      onClose();
    } catch (e) {
      logger.warn(`Failed to ${isEdit ? "update" : "add"} source`, e);
      setError(e instanceof ApiError ? e.message : `Failed to ${isEdit ? "update" : "add"} source`);
    } finally {
      setSubmitting(false);
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <h2>{isEdit ? "Edit source" : "Add log source"}</h2>
        {error && <div className="error-banner">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label htmlFor="source-name">Name</label>
            <input
              id="source-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              placeholder="e.g. prod-web1 access log"
            />
          </div>

          <div className="form-field">
            <label htmlFor="source-type">Type</label>
            <select
              id="source-type"
              value={type}
              onChange={(e) => setType(e.target.value as SourceType)}
            >
              {TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          {type === "SFTP" && (
            <>
              <div className="form-row">
                <div className="form-field">
                  <label htmlFor="source-host">Host</label>
                  <input
                    id="source-host"
                    value={host}
                    onChange={(e) => setHost(e.target.value)}
                    placeholder="10.0.0.5"
                    required
                  />
                </div>
                <div className="form-field" style={{ maxWidth: 100 }}>
                  <label htmlFor="source-port">Port</label>
                  <input
                    id="source-port"
                    type="number"
                    value={port}
                    onChange={(e) => setPort(e.target.value)}
                  />
                </div>
              </div>
              <div className="form-row">
                <div className="form-field">
                  <label htmlFor="source-username">Username</label>
                  <input
                    id="source-username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    required
                  />
                </div>
                <div className="form-field">
                  <label htmlFor="source-password">Password</label>
                  <input
                    id="source-password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder={isEdit ? "Leave blank to keep current password" : undefined}
                  />
                </div>
              </div>
            </>
          )}

          <div className="form-field">
            <label htmlFor="source-path">
              {type === "HTTP"
                ? "URL"
                : type === "SFTP"
                  ? "Remote path"
                  : "Path"}
            </label>
            <div className="form-field-with-action">
              <input
                id="source-path"
                value={path}
                onChange={(e) => setPath(e.target.value)}
                placeholder={
                  type === "HTTP"
                    ? "https://example.com/logs/app.log"
                    : type === "SFTP"
                      ? "/var/log/nginx/access.log"
                      : "/var/log/app.log"
                }
                required
              />
              {isLocal && (
                <button type="button" className="btn btn-secondary" onClick={() => setBrowsing(true)}>
                  Browse…
                </button>
              )}
            </div>
          </div>

          <div className="form-actions">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? (isEdit ? "Saving…" : "Adding…") : isEdit ? "Save" : "Add source"}
            </button>
          </div>
        </form>
      </div>
      {browsing && (
        <SourceBrowseDialog
          mode={type === "LOCAL_FILE" ? "file" : "directory"}
          initialPath={path || undefined}
          onSelect={setPath}
          onClose={() => setBrowsing(false)}
        />
      )}
    </div>,
    document.body,
  );
}

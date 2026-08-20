import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { createPortal } from "react-dom";
import type { DirectoryEntry, DirectoryListing } from "../api/types";
import { ApiError, browseDirectory } from "../api/client";
import { createLogger } from "../utils/logger";
import { FileIcon, FolderIcon, UpFolderIcon } from "./icons";

const logger = createLogger("SourceBrowseDialog");

interface SourceBrowseDialogProps {
  /** "file" lets the user pick a file (directories are navigable but not selectable); "directory" only lets them pick the current directory itself. */
  mode: "file" | "directory";
  initialPath?: string;
  onSelect: (path: string) => void;
  onClose: () => void;
}

export function SourceBrowseDialog({ mode, initialPath, onSelect, onClose }: SourceBrowseDialogProps) {
  const [currentPath, setCurrentPath] = useState<string | undefined>(initialPath || undefined);
  const [pathInput, setPathInput] = useState(initialPath ?? "");
  const [listing, setListing] = useState<DirectoryListing | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    browseDirectory(currentPath)
      .then((result) => {
        if (cancelled) return;
        setListing(result);
        setPathInput(result.path);
      })
      .catch((e) => {
        if (cancelled) return;
        logger.warn("Failed to browse directory", e);
        setError(e instanceof ApiError ? e.message : "Failed to browse directory");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [currentPath]);

  const navigateTo = (path: string) => setCurrentPath(path);

  const handlePathSubmit = (e: FormEvent) => {
    e.preventDefault();
    navigateTo(pathInput);
  };

  const handleEntryClick = (entry: DirectoryEntry) => {
    if (entry.directory) {
      navigateTo(entry.path);
    } else if (mode === "file") {
      onSelect(entry.path);
      onClose();
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel browse-dialog" onClick={(e) => e.stopPropagation()}>
        <h2>{mode === "file" ? "Select a file" : "Select a directory"}</h2>

        <form className="browse-path-row" onSubmit={handlePathSubmit}>
          <input
            className="input"
            value={pathInput}
            onChange={(e) => setPathInput(e.target.value)}
            placeholder="/var/log"
            aria-label="Path"
          />
          <button type="submit" className="btn btn-secondary">
            Go
          </button>
        </form>

        {error && <div className="error-banner">{error}</div>}

        <div className="browse-list">
          {loading && <div className="browse-empty text-muted">Loading…</div>}
          {!loading && !error && listing && (
            <>
              {listing.parent !== null && (
                <button type="button" className="browse-entry" onClick={() => navigateTo(listing.parent!)}>
                  <UpFolderIcon size={15} />
                  <span className="browse-entry-name">..</span>
                </button>
              )}
              {listing.entries.length === 0 && listing.parent === null && (
                <div className="browse-empty text-muted">Empty directory</div>
              )}
              {listing.entries.map((entry) => (
                <button
                  type="button"
                  key={entry.path}
                  className={`browse-entry${!entry.directory && mode === "directory" ? " browse-entry-disabled" : ""}`}
                  onClick={() => handleEntryClick(entry)}
                  disabled={!entry.directory && mode === "directory"}
                >
                  {entry.directory ? <FolderIcon size={15} /> : <FileIcon size={15} />}
                  <span className="browse-entry-name">{entry.name}</span>
                </button>
              ))}
            </>
          )}
        </div>

        <div className="form-actions">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          {mode === "directory" && (
            <button
              type="button"
              className="btn btn-primary"
              disabled={!listing}
              onClick={() => {
                if (!listing) return;
                onSelect(listing.path);
                onClose();
              }}
            >
              Select this folder
            </button>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}

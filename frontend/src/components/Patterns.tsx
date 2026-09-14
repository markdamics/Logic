import { useMemo } from "react";
import { Sparkline } from "./Sparkline";
import { PatternsIcon } from "./icons";
import type { LogSource, LogTemplate, TemplateSort } from "../api/types";

interface PatternsProps {
  sources: LogSource[];
  templates: LogTemplate[];
  loading: boolean;
  source: string | undefined;
  sort: TemplateSort;
  onSourceChange: (source: string | undefined) => void;
  onSortChange: (sort: TemplateSort) => void;
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function Patterns({ sources, templates, loading, source, sort, onSourceChange, onSortChange }: PatternsProps) {
  const sourceNames = useMemo(() => Array.from(new Set(sources.map((s) => s.name))).sort(), [sources]);

  return (
    <div className="alerts-screen">
      <div className="alerts-toolbar">
        <select className="input" value={source ?? ""} onChange={(e) => onSourceChange(e.target.value || undefined)}>
          <option value="">All sources</option>
          {sourceNames.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
        <select className="input" value={sort} onChange={(e) => onSortChange(e.target.value as TemplateSort)}>
          <option value="volume">Sort by volume</option>
          <option value="recent">Sort by most recently new</option>
        </select>
      </div>

      {!loading && templates.length === 0 && (
        <div className="alerts-placeholder">
          <div className="alerts-placeholder-panel">
            <PatternsIcon size={28} />
            <h4>No patterns mined yet</h4>
            <p className="text-muted">
              Repeated log message shapes are clustered here automatically as sources are ingested — no manual
              grepping required. Give it a few index cycles once a source has traffic.
            </p>
          </div>
        </div>
      )}

      {templates.length > 0 && (
        <div className="log-table-wrapper">
          <table className="log-table entries-table">
            <thead>
              <tr>
                <th>Template</th>
                {!source && <th>Source</th>}
                <th>Occurrences</th>
                <th>Trend</th>
                <th>First seen</th>
                <th>Last seen</th>
                <th>Sample</th>
              </tr>
            </thead>
            <tbody>
              {templates.map((template) => (
                <tr key={template.id} className="log-row">
                  <td>
                    <code>{template.templateText}</code>
                  </td>
                  {!source && <td className="text-muted">{template.source}</td>}
                  <td>{template.occurrenceCount.toLocaleString()}</td>
                  <td>
                    <Sparkline values={template.history} />
                  </td>
                  <td className="text-muted">{formatTimestamp(template.firstSeenAt)}</td>
                  <td className="text-muted">{formatTimestamp(template.lastSeenAt)}</td>
                  <td className="text-muted" title={template.sampleRawLine ?? undefined}>
                    {template.sampleRawLine}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

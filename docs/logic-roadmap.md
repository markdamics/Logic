# Logic Roadmap & Draft Tickets

Grounded in the current codebase (Java/Spring backend + React/TS frontend), not
just the raw wishlist in logic-features-todo.md. This is a single-admin,
self-hosted desktop log analyzer, already with:

- Local/SFTP/HTTP tail ingestion (LogTailReader, TailSource implementations)
- Query engine with Lucene/SPL/LogQL query languages + saved searches
- Threshold + anomaly (std-dev) alerting with mute/events
- Dashboard with file/source activity and bar charts
- Live view via 3s polling (not push)
- HTTP Basic auth, single in-memory admin user, no RBAC by design

That last point matters: RBAC, SIEM/MITRE mapping, tiered storage, and
cost/cardinality management assume multi-tenant/enterprise scale this tool
isn't built for, so they're intentionally excluded below.

## Prioritized Roadmap

**P0 — Real-time core (fits architecture, unlocks everything else)**

1. Replace polling with SSE push for live tail
2. Virtualized log viewer for the results table
3. Client-side regex/level filtering that doesn't interrupt the live stream

**P1 — High value, moderate effort**

4. Real-time rate/error histogram (Canvas-based)
5. Log retention/rotation policy
6. PII masking/redaction for ingested fields

**P2 — Worth doing, bigger lift**

7. Repeat/pattern clustering for noisy log lines (log template mining)
8. Natural-language → query-language translation
9. Cross-source correlation ("also happened" for a selected log line)
10. Admin audit trail (config/alert-rule changes)

**P3 — From the feature survey, not yet scoped**

11. Custom dashboard builder (drag-and-drop time-series/heatmap/pie panels)
12. Ingest-time log sampling for volume control
13. ML-based anomaly detection (replacing/augmenting the std-dev baseline)
14. Automatic cross-service root-cause correlation
15. Predictive capacity/failure analytics
16. Public API for third-party integrations

Out of scope for now: RBAC, SIEM/MITRE mapping, tiered storage,
cost/cardinality management, plugin ecosystem, correlation with external
metrics/traces (no metrics or tracing ingestion exists — this tool only ingests
logs).

---

## Draft Tickets

### LOGIC-101 — SSE endpoint for live log - done

Replace 3s polling in LogStream.tsx with a push-based stream.

- Add GET /api/logs/stream (SSE) in LogStreamController, backed by
  LogQueryService, filtered server-side by the same params as GET /api/logs
  (search, level, source, file).
- Emit new entries as they arrive from LogTailReader/TailSource implementations
  instead of re-querying.
- Frontend: replace LIVE_POLL_INTERVAL_MS polling loop with EventSource,
  reconnect on drop with backoff.
- AC: opening Live view shows new lines within ~200ms of ingestion; closing the
  tab cleanly unsubscribes; reconnects after a server restart.
- Effort: M/L

### LOGIC-102 — Virtualized log table - done

Table currently renders full page (up to 100 rows); needs to hold thousands
without degrading.

- Introduce @tanstack/react-virtual (or react-window) in LogStream.tsx results
  table.
- Preserve existing sort/column behavior (SortColumn/SortDirection).
- AC: scrolling 10k+ buffered rows stays >50fps; memory stays flat as new SSE
  rows append (old rows evicted past a configurable buffer, e.g. 5000 lines).
- Effort: M
- Depends on: LOGIC-101

### LOGIC-103 — Client-side filter over live buffer - done

Filtering/regex should apply instantly to the in-memory live buffer, without a
round-trip or stream restart.

- Reuse existing search/level filter UI; apply as a pure client-side predicate
  over the SSE buffer instead of re-issuing fetchLogs.
- Keep server-side filtering for the initial page load / non-live (paginated)
  mode.
- AC: typing in the search box while streaming filters visible rows with no
  flicker or stream interruption.
- Effort: S
- Depends on: LOGIC-101, LOGIC-102

### LOGIC-104 — Real-time rate/error histogram - done

BarChart.tsx is static/dashboard-only; add a live-updating rate chart for the
streaming view.

- Canvas-based bucketed histogram (e.g. count per 1s/10s bucket) fed by the SSE
  buffer, split by level.
- Rolling window (last N minutes), no unbounded growth.
- AC: error spikes visibly appear within a few seconds of ingestion; chart
  doesn't leak memory over a multi-hour session.
- Effort: M
- Depends on: LOGIC-101

### LOGIC-105 — Log retention policy - done

Nothing currently caps how much ingested log data accumulates.

- Add a configurable retention window (e.g. app.retention.days) and a
  scheduled cleanup job.
- Surface current storage usage / oldest retained entry on the Dashboard.
- AC: entries older than the configured window are purged on schedule; setting
  is documented in application.yml.
- Effort: S/M
- Note: the retention window + scheduled purge already existed
  (`app.search.retention-days` / `app.search.purge-interval-ms`,
  `RetentionPurgeJob`, documented in application.yml/README) since the search
  index is the only durable store of log content - this ticket added the
  missing half: `SearchIndexStatsService` (index size on disk + oldest
  indexed entry's timestamp) surfaced as two new Dashboard stat cards.

### LOGIC-106 — PII masking/redaction on ingest - done

SFTP/HTTP credentials are already encrypted (EncryptedStringConverter);
message content isn't touched.

- Configurable regex-based redaction rules (e.g. emails, credit card numbers,
  API keys) applied at ingest time in LogIngestionService, before persistence.
- Redaction rules manageable per-source or globally, off by default.
- AC: a log line matching a configured pattern is stored/displayed with the
  match masked; raw value never persisted.
- Effort: M
- Note: new `RedactionRule` entity/repository/service/controller (CRUD at
  `/api/redaction/rules`, scoped like AlertRule's `source` field - null =
  global), a Flyway `V4__create_redaction_rule.sql` migration, and a
  Redaction sidebar screen for managing rules. `LogIngestionService` compiles
  applicable rules once per source read (not per line) and masks each
  parsed message before a `LogEntry` is ever constructed, so the raw value
  never reaches the cache, the durable search index, or the UI. A fresh
  install ships with zero rules, so nothing is masked until one is added.

### LOGIC-107 — Repeat/pattern clustering for noisy log lines (template mining) - done

MessageFieldExtractor (search/extract) classifies and extracts fields from
one line at a time (JSON/syslog/access-log/logfmt) but never compares lines
to each other. There's no way today to see "these 4,000 lines are the same
underlying event" or to notice a genuinely new error shape without manually
grepping and eyeballing counts — the unit of analysis is a single LogEntry,
and there's no trace/request-id in most ingested sources to group by instead.

- Priority note: this is a prerequisite mental model for LOGIC-109
  (cross-source correlation) and LOGIC-114 (root-cause correlation) — both
  assume you've already collapsed repeats by hand before clicking a
  representative line. Sequencing it ahead of those (and ahead of
  LOGIC-108's NL query layer, which is more useful once there's a
  de-noised set of patterns to query over) is why it's placed here.
- Incremental streaming template miner (Drain-style), not a full grok/ML
  engine: tokenize each message, mask high-cardinality tokens (numbers,
  UUIDs, IPs, timestamps, quoted strings — reuse MessageFieldExtractor's
  structured fields where a format was already detected instead of
  re-deriving them), then match against a per-LogSource template tree within
  a similarity threshold; no match mints a new template.
- Run on the ingest path (LogIngestionService) or as an async pass right
  behind it, so it never blocks the live SSE tail (LOGIC-101).
- Persist templates per LogSource with an occurrence count, first/last-seen
  timestamp, and one sample raw line; expose via a new TemplateService/
  endpoint rather than overloading LogQueryService.
- Must degrade gracefully across a deploy that changes a source's log shape:
  matching is token/similarity-based, not a fixed per-source regex, so a
  line with an added/removed field either still matches the closest existing
  template loosely or forks a new template going forward — either way every
  line lands in exactly one template, never silently falls into an
  "unstructured, ungrouped" bucket the way a rigid schema would.
- Template counts should age out under the existing retention policy
  (LOGIC-105) rather than growing unbounded, and should run after redaction
  (LOGIC-106) so sample lines don't retain PII.
- UI: new "Patterns" view listing templates as collapsed groups (template
  text + occurrence count + trend sparkline), sortable by volume or
  most-recently-new-template — this is the primary surface that replaces the
  manual grep loop.
- AC: ingesting a source with 3 distinct message shapes at varying
  frequencies clusters into exactly 3 templates with correct counts;
  introducing a 4th shape mid-stream (simulating a deploy that alters the log
  format) produces a 4th template rather than crashing or dropping those
  lines into an unstructured catch-all.
- Effort: L (needs scoping — algorithm/threshold choice, storage schema,
  ingest-path performance impact under load)
- Depends on: LOGIC-105 (retention/eviction of template counts); should land
  after LOGIC-106 (redaction) so template samples stay PII-safe.
- Note: new `com.logic.analyzer.template` package (`LogTemplate`
  entity/repository, `TemplateTokenizer`, `TemplateMiningService`,
  `TemplateService`/`TemplateController` at `/api/templates`,
  `TemplateRetentionJob`), a Flyway `V5__create_log_template.sql` migration,
  and a "Patterns" sidebar screen (table + inline SVG trend sparkline). The
  tokenizer masks numbers/UUIDs/IPs/hex blobs/quoted strings/mixed
  alphanumeric ids to fixed placeholders; the miner then buckets per-source
  templates by token count and folds a candidate in via a >=50% token
  similarity threshold, widening any differing position to a wildcard on
  merge (a simplified Drain). `SearchIndexService` calls the miner once per
  (source, file) reindex pass rather than per LogEntry, because that pass
  re-adds a file's *entire* current tail window every time its fingerprint
  changes, not just newly appended lines - so occurrence counts come from
  diffing each distinct message text's count against the previous pass's for
  that file and mining only the positive delta, which correctly handles both
  "same tail window reprocessed" (delta 0, no inflation) and "N genuinely
  repeated identical lines newly appended" (delta N, not collapsed to one).
  History for the trend sparkline is a capped 20-entry ring of per-minute
  occurrence counts rolled forward incrementally on the entity itself.
  Retention reuses `app.search.retention-days`/`purge-interval-ms` rather
  than a new config key, deleting templates by `lastSeenAt` and invalidating
  the miner's in-memory per-source cluster cache so it can't silently
  update-into a since-deleted row.
- Follow-up: review flagged that this doesn't yet replace a manual grep loop
  end to end - no drill-down from a pattern to its matching lines, no
  new-pattern alerting, no manual merge/split correction, and no config/load
  validation of the mining thresholds. Planned as LOGIC-117 through
  LOGIC-120 in `docs/logic-pattern-drilldown-plan.md`.

### LOGIC-108 — Natural language → query language

Three query languages already exist (Lucene/SPL/LogQL); add a plain-English
entry point that compiles to one of them.

- New endpoint or client-side call to translate a NL prompt into the
  currently-selected QueryLanguage, shown to the user as an editable query
  before running (never auto-executed blind).
- AC: "show me errors from payments-api in the last hour" produces a correct,
  editable Lucene/SPL/LogQL query.
- Effort: M/L (depends on LLM provider/cost decision — needs scoping before
  estimating further)

### LOGIC-109 — Cross-source correlation on a log line

No way today to see "what else happened around this event" across sources.

- From a selected LogEntry, query other sources/files within a small time
  window (e.g. ±5s) and surface related entries.
- AC: clicking a log row shows a "nearby events" panel with entries from other
  sources in the same window.
- Effort: M

### LOGIC-110 — Admin action audit trail

Single-admin model still benefits from an audit log for accountability (config
drift, who changed an alert rule and when).

- Log create/update/delete on LogSource, AlertRule (and mutation of
  retention/redaction settings once built) to an append-only audit table.
- Simple read-only view in the UI.
- AC: every source/alert-rule change is recorded with timestamp and old/new
  values; audit log itself isn't editable via the API.
- Effort: S/M

### LOGIC-111 — Custom dashboard builder

Dashboard.tsx currently renders a single fixed layout (source/status list +
BarChart); no way to add, remove, or rearrange panels.

- Introduce a panel model (time-series, heatmap, pie/breakdown-by-level)
  backed by DashboardService, with layout (position/size) persisted per admin.
- Drag-and-drop panel arrangement in the UI; each panel independently
  configurable (source, time range, chart type).
- AC: adding/removing/reordering panels persists across reloads; existing
  source/status overview ships as a default panel set.
- Effort: L

### LOGIC-112 — Ingest-time log sampling

LogIngestionService persists every ingested entry; high-volume sources have no
way to cap storage/index growth short of full retention deletion.

- Configurable sampling strategy per LogSource (e.g. 1-in-N, or rate-limit per
  level) applied before persistence/indexing.
- Sampling decisions should never drop ANOMALY/THRESHOLD-relevant entries
  silently — document the tradeoff, default sampling off.
- AC: a source configured at, e.g., 1-in-10 sampling persists ~10% of lines;
  sampled-out count is visible on the Dashboard/source card.
- Effort: M

### LOGIC-113 — ML-based anomaly detection

AlertRule ANOMALY type currently flags a window when it exceeds mean +
k*stddev of prior windows (AlertEvaluationService); this misses patterns a
fixed baseline can't capture (seasonality, slow drift).

- Add an alternative anomaly model (e.g. EWMA or seasonal baseline) as a new
  AlertRule strategy, selectable alongside the existing std-dev baseline.
- Keep the existing THRESHOLD/std-dev ANOMALY rules working unchanged.
- AC: a rule using the new model correctly flags a seasonal spike that a flat
  std-dev baseline would miss, on a fixture with known daily periodicity.
- Effort: L
- Depends on: LOGIC-105 (enough retained history to baseline against)

### LOGIC-114 — Automatic root-cause correlation

LOGIC-109 surfaces nearby events only when the admin selects a log line;
there's no automatic "this alert firing is probably caused by that" signal.

- When an AlertRule fires, automatically query other sources/files in the same
  time window and rank likely-related entries (e.g. by co-occurring error
  spikes) instead of waiting for a manual click.
- Surface the ranked candidates on the alert event itself.
- AC: a fired alert event shows a ranked "likely related" list from other
  sources without any manual interaction.
- Effort: L
- Depends on: LOGIC-109

### LOGIC-115 — Predictive capacity/failure analytics

No forward-looking view exists today — Dashboard only summarizes the last 24h
(DashboardService).

- Trend ingestion volume and error rate per source over time; project
  short-horizon storage growth and flag sources trending toward
  threshold/anomaly breach.
- Surface as a forecast panel, not a blocking gate — predictions are advisory
  only.
- AC: Dashboard shows a projected-storage-growth trend line; a source with a
  rising error rate is flagged before it crosses its alert threshold.
- Effort: L (needs scoping — model choice, minimum history required)
- Depends on: LOGIC-105

### LOGIC-116 — Public API for third-party integrations

Existing REST endpoints (LogStreamController, AlertRuleController, etc.) are
consumed only by the bundled frontend; no stable/documented surface exists for
external tools.

- Formalize a versioned subset of the existing API (read-only log query, alert
  events) as a documented public API, distinct from internal frontend-only
  endpoints.
- Deliberately excludes a plugin/extension runtime — out of scope per the
  single-admin desktop scope this tool targets.
- AC: documented endpoints are stable across releases and covered by contract
  tests separate from internal endpoint changes.
- Effort: M

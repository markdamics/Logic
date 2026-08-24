# <img src="frontend/public/logic-mark.svg" width="28" height="28" alt="" valign="middle" /> Logic

Log Analyzer — a full-stack app for registering log sources and inspecting
their content in real time.

Core features: source management, real log ingestion with
filtering/sorting/pagination, a live-updating Log Stream, a Dashboard
overview, a durably indexed Search & Query bar (Lucene syntax plus SPL and
LogQL subsets, with count/rate/numeric-stat aggregation charts), threshold
and anomaly Alerting with webhook notifications, log-native observability
(trace-ID correlation across sources and an optional APM deep-link), and
Saved Searches — styled after the Axiom HUD design system, with four
selectable themes.

## Features

### Log Stream

- Real ingestion: tails the trailing window of each source (bounded reads —
  512KB / 3,000 lines per file) rather than loading it whole, with
  level/timestamp/stack-trace-continuation parsing that recognizes this
  app's own log format, ISO-8601, Apache/Combined access logs, and
  BSD/RFC-3164 syslog (falling back to keyword-based severity — "failed",
  "denied", "exited on signal", etc. — when a line carries no explicit
  level tag, as bare syslog lines don't).
- Structured/unstructured message parsing: click a row to expand it and see
  the full message (not just the truncated single line) alongside its
  auto-detected format — JSON, syslog, Apache/Nginx access log, or
  logfmt/key=value — broken out into its individual fields. Multiple rows
  can be expanded at once; anything that doesn't match a known shape shows
  as plain unstructured text.
- Filtering by free-text search (debounced), source, file, severity
  (ERROR/WARN/INFO/DEBUG), and time range, plus one-click presets
  (all errors, errors & warnings, clear).
- Sortable columns (time, level, source, file), configurable rows per page
  (10/25/50/100), and backend-side pagination.
- Auto-refreshes on its own while any live source is in scope; shows a "●
  LIVE" indicator when doing so.
- A "new data available" banner (naming the exact file and source) appears
  above the table when a non-live source has changed, with a one-click
  Reload.

  ![log stream dark](/Screenshots/logstream_dark_1.png)

### Search & Query

- Log entries are durably indexed (embedded Apache Lucene, no external
  service) in the background as sources are read, independent of the Log
  Stream's live/non-live cache — this is what backs both the plain filters
  above and the query bar below, and gives the app history beyond whatever's
  currently in a source's trailing read window.
- Structured fields extracted from JSON, syslog, access-log, and logfmt
  messages (the same detection the expanded row view uses) are individually
  indexed as `field.<name>`, so they're filterable/queryable on their own,
  not just visible in the expanded row.
- A query-bar mode (toggle next to the free-text filters) accepts one of
  three languages, selected from a dropdown:
  - **Lucene** — full native Lucene classic query syntax (`field:value`,
    boolean operators, phrases, wildcards, etc.) against `_all` or any
    specific field.
  - **SPL (subset)** — `field=value`, comparisons (`status>=500`),
    `AND`/`OR`/`NOT`, quoted phrases, and one aggregation stage: either
    `| stats count by <field>`, or a numeric statistic over a structured
    numeric field — `| stats avg|min|max|sum|p50|p95|p99(field.<name>) [by
    <field>]` (the `by <field>` clause is optional; omitting it computes one
    aggregate across every matched entry instead of grouping).
  - **LogQL (subset)** — label selectors (`{level="ERROR"}`), line filters
    (`|=` contains, `|~` regex), and one aggregation stage: `count_over_time`,
    `rate` (`count_over_time({...}[5m])`, `rate({...}[5m])`), or a numeric
    statistic time-bucketed the same way —
    `avg|min|max|sum|p50|p95|p99_over_time(field.<name>{...}[5m])`.
- Aggregating queries render as a bar chart in place of the row table instead
  of a flat count — grouped/time-bucketed counts and rates as before, plus
  the avg/min/max/sum/percentile value for a numeric-stats query.
- **Trace correlation** — a structured field that looks like a correlation ID
  (`trace_id`, `request_id`, `correlation_id`, `span_id`, `req_id`, matched
  case-insensitively) gets a "Correlate" button in the expanded row view;
  clicking it switches to Lucene query mode and jumps to every log line
  across every source that shares that exact value, in chronological order.
- **APM deep-link** — when `APM_TRACE_URL_TEMPLATE` is configured (see
  [Security](#security)), the same correlation fields also render an "Open in
  APM ↗" link that substitutes the field's value into the template and opens
  it in a new tab — a stateless link-out, not a data pull from the APM tool.
- **Saved Searches** — bookmark the current filters (Simple mode) or query-bar
  string (Lucene/SPL/LogQL mode, including its language and aggregation) under
  a name, shown as chips next to the filter bar. Click a saved search to
  re-run it, or use its link icon to copy a `?savedSearch=<id>` URL that
  reopens straight into that saved search's filters and results — the
  sharing model is a stable link, not a per-user "my searches" list (see
  [Known simplifications](#known-simplifications-first-phase)).

### Alerting

- Rules watch a query-bar query or Simple-mode filter (same scope shape as a
  Saved Search) over a rolling time window and evaluate one of two ways:
  - **Threshold** — fires when the window's count (or rate) crosses a fixed
    comparison (`>`, `>=`, `<`, `<=`, `=`) against a number you set. Covers
    both error-spike and specific-pattern alerts — a pattern alert is just a
    threshold rule whose query is the pattern, with a `count >= 1` threshold.
  - **Anomaly** — fires when the window's count is more than *k* standard
    deviations above the mean of a configurable number of prior windows (a
    statistical baseline, not ML).
- **Webhook notifications** — an optional per-rule URL is POSTed a JSON
  payload on trigger, HMAC-SHA256-signed (`X-Logic-Signature: sha256=...`)
  with a per-rule secret so the receiving end can verify authenticity; the
  secret is encrypted at rest the same way SFTP passwords are. A "test
  webhook" action sends a synthetic payload without waiting for a real
  trigger.
- **Mute / unmute** — a muted rule keeps evaluating (so its trigger history
  stays accurate) but never sends a webhook.
- Trigger history per rule (timestamp, the metric value that crossed the
  threshold) and a last-evaluated timestamp for visibility into whether a
  rule is actually running.

### Dashboard

- Stat cards: total/enabled/disabled sources, reachable sources, log entries,
  errors, and warnings (last 24h).
- "Errors by file" bar chart (top files by error count, last 24h).
- Source activity table (entries/errors per source, live/enabled/status
  badges).
- Recent issues feed (latest errors & warnings across all sources).
- Same live auto-refresh and "new data available" banner as the Log Stream.

  ![dashboard dark](/Screenshots/dashboard_dark_1.png)

### Source management (Sources screen)

- Register log sources of six types: a local file, a local directory (read
  non-recursively, capped to the 20 most recently modified files), an SFTP
  remote path, a plain HTTP(S) URL, or an uploaded file/directory from your
  *browser's* machine (as opposed to every other type, which references a
  path on the server the backend runs on, or a remote host).
- **Upload file / Upload directory** — sends file(s) straight from your
  browser to the server (`POST /api/sources/upload`, multipart), which stores
  them under `UPLOADS_DIR` and reads them exactly like a local file/directory
  source from then on. Because it's a one-time snapshot with nothing external
  left to poll, uploaded sources have no **Live** toggle; use the shared
  Reload action to re-read the same stored copy (e.g. after a parsing fix) —
  not to pull in new content, since there is none to pull. Uploaded content
  can't be replaced in place: delete the source and upload again to change
  it. Directory uploads inherit the same non-recursive read as
  `LOCAL_DIRECTORY` above, so files inside a nested subfolder are stored on
  disk but not ingested — flatten the folder before uploading if you need
  every file read.
- **Browse…** — for a local file/directory source, a picker (backed by
  `GET /api/sources/browse`) lists the *server's* filesystem so the path can
  be navigated to rather than typed from memory; a directory source's picker
  only lets you select a folder, a file source's picker only lets you select
  a file. It browses the machine the backend runs on, not the browser's
  machine — the two are the same host in the common single-box/Docker
  deployment, but not necessarily when frontend and backend are split
- Edit, delete, and test connectivity (`UNVERIFIED` / `REACHABLE` /
  `UNREACHABLE`) for any source.
- **Enable / disable** a source — disabled sources are skipped by ingestion
  entirely (paused) and their already-indexed log lines stop appearing in the
  Log Stream, query-bar results, and alert evaluation immediately, everywhere
  in the app. Nothing is deleted from the index though: re-enabling brings
  everything straight back (no re-ingest wait), unlike removing a source
  entirely, which does purge its indexed data.
- **Live** toggle — live sources are re-read continuously (~2s) so the Log
  Stream and Dashboard update on their own; non-live sources are read once and
  frozen as a fixed snapshot until reloaded.
- **New data available** indicator — a non-live source whose underlying
  file(s) have changed since it was last read is flagged (per file, even
  inside a directory source), prompting a reload rather than silently going
  stale.

  ![log source dark 1](/Screenshots/sources_dark_1.png)

### Shared Reload action

A Reload button (Log Stream and Dashboard) invalidates the ingestion cache so
non-live sources are re-read on demand — the deliberate counterpart to "live"
sources, which never need it.

### Appearance

Styled after the **Axiom HUD design system**: sharp shaved-corner panels
(`clip-path`, not rounded corners), mono-readout typography for timestamps
and stat values, and four selectable themes — click the theme chip at the
bottom of the sidebar to cycle:

| Theme | Look |
| --- | --- |
| NULLGRID (default) | Matte black + electric blue, dark |
| GANTRY | Bone white + safety orange, light |
| ABYSSAL | Deep navy + neon cyan, dark |
| RAVEN | Near-black + hot magenta, dark |

## Stack

- **Backend**: Java 25, Spring Boot 4, Maven, Spring Data JPA, H2 (embedded,
  file-based), Flyway (schema migrations), Apache Lucene (embedded search
  index, `lucene-core`/`lucene-analysis-common`/`lucene-queryparser`/
  `lucene-facet`), sshj (SFTP)
- **Frontend**: TypeScript, React, Vite, plain CSS (design tokens — color,
  spacing, radius, shadow/glow, typography — in `theme.css`, following the
  Axiom HUD design system; see [Appearance](#appearance) above)

## Running in dev

Backend (port 8080):

```bash
cd backend
mvn spring-boot:run
```

Frontend (port 5173):

```bash
cd frontend
npm install
npm run dev
```

The frontend dev server proxies `/api/*` requests to the backend, so no CORS
configuration changes are needed for normal dev use. Open
<http://localhost:5173> in a browser — the browser will prompt for
credentials (see [Security](#security) below; default in dev is
`admin` / `admin`).

## Deployment

A multi-stage `Dockerfile` builds the frontend, bundles it as Spring Boot
static resources, and packages the backend into one self-contained jar/image
— a single container serves both the UI (`/`) and the API (`/api/*`) from
the same origin, so there's no CORS or reverse-proxy setup to do.

```bash
docker compose up --build
```

This starts Logic on <http://localhost:8080> with a named volume
(`logic-data`) for its durable state (H2 database, encryption key, Lucene
search index — everything under `./data`, per [Security](#security) and
[Search & Query](#search--query) above). Two things to set before exposing
this beyond localhost:

- **`ADMIN_PASSWORD`** — `docker-compose.yml` ships with a placeholder
  (`change-me`) and `AUTH_ENABLED=true`; override it via an env var or `.env`
  file rather than editing the compose file in place.
- **Log source volumes** — Logic only reads log files, it doesn't ship any
  itself, so bind-mount whatever directories/files you want it to tail (see
  the commented example in `docker-compose.yml`) — the path you register as
  a source must match the path *inside* the container, not on the host.

Building the image directly (no compose) works the same way any Dockerfile
does: `docker build -t logic .` then `docker run -p 8080:8080 -v
logic-data:/app/data logic`.

## Desktop

A native Linux build (Tauri) is available for running Logic without Docker —
it bundles the same backend jar with a trimmed JRE and shows the UI in a
native window. See [`desktop/README.md`](desktop/README.md) for building it.

## Security

Every API request requires HTTP Basic auth by default — there's no separate
login page, so the browser's native credential prompt covers the whole app.
This and the rest of the security-relevant behavior is controlled by
environment variables (or the matching `app.*` / `spring.*` property in
`application.yml`):

| Variable | Default | Purpose |
| --- | --- | --- |
| `AUTH_ENABLED` | `true` | Set to `false` to disable HTTP Basic auth entirely — e.g. if the instance already sits behind its own access control. With it off, every endpoint is open to anyone who can reach the instance, so only disable it on a network you trust. |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin` | The single admin account's API credentials. A startup warning is logged whenever the password is left at its default — always override it before exposing the instance beyond localhost. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated list of origins allowed to call the API. Set this to the frontend's real origin(s) when frontend and backend are deployed separately. |
| `ENCRYPTION_KEY` | *(generated)* | Base64-encoded 32-byte AES key used to encrypt SFTP passwords at rest. If unset, a key is generated on first run and saved to `./data/encryption.key`; losing that file (without `ENCRYPTION_KEY` set) makes saved SFTP passwords unrecoverable. Set this explicitly in production so the key doesn't depend on that file surviving. |
| `H2_CONSOLE_ENABLED` | `false` | Exposes the H2 database admin console at `/h2-console` (still behind auth). Leave off in production; only useful for local debugging. |

The search index is configured the same way:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SEARCH_INDEX_DIR` | `./data/search-index` | Where the embedded Lucene index lives on disk. It's rebuildable, not a source of truth — deleting it (app stopped) just costs a full reindex from whatever's currently readable in each source, same as losing `./data/encryption.key` costs a new key. |
| `SEARCH_INDEX_INTERVAL_MS` | `5000` | How often enabled sources are re-scanned and reindexed in the background, independent of the Log Stream's live/non-live cache. |
| `SEARCH_INDEX_RETENTION_DAYS` | `30` | Max age (days) an indexed entry is kept before the retention sweep purges it; `0` means unlimited. |
| `SEARCH_INDEX_PURGE_INTERVAL_MS` | `3600000` | How often the retention sweep runs. |

Observability is configured the same way:

| Variable | Default | Purpose |
| --- | --- | --- |
| `APM_TRACE_URL_TEMPLATE` | *(unset)* | A URL template for the "Open in APM ↗" link (see [Search & Query](#search--query) above), e.g. `https://app.datadoghq.com/apm/trace/{traceId}`. The literal `{traceId}` is replaced with the correlation field's value. Leave unset to hide the link entirely. |

Uploaded sources are configured the same way:

| Variable | Default | Purpose |
| --- | --- | --- |
| `UPLOADS_DIR` | `./data/uploads` | Where uploaded source files/directories are stored on disk after upload (see [Source management](#source-management-sources-screen) above). Rebuildable only in the sense that it holds the actual uploaded bytes — unlike the search index, deleting it loses the underlying content itself, not just a derived index. |
| `UPLOAD_MAX_FILE_SIZE` | `100MB` | Max size of a single uploaded file. |
| `UPLOAD_MAX_REQUEST_SIZE` | `500MB` | Max size of the whole upload request — matters most for a directory upload, which sends every file in one request. |

These are flagged for hardening in a later phase (per-user auth,
key-based SFTP auth, known-hosts verification, recursive/streaming
ingestion, etc.).

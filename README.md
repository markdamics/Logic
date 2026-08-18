# <img src="frontend/public/terminal-svgrepo-com.svg" width="28" height="28" alt="" valign="middle" /> Logic

Log Analyzer — a full-stack app for registering log sources and inspecting
their content in real time.

Core features: source management, real log ingestion with
filtering/sorting/pagination, a live-updating Log Stream, a Dashboard
overview, and a durably indexed Search & Query bar (Lucene syntax plus SPL
and LogQL subsets, with aggregation charts). Saved/shareable searches and
alerting are planned for later phases.

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

  ![log stream light](/Screenshots/Screenshot_20260808_015244.png)
  ![log stream dark](/Screenshots/Screenshot_20260808_091518.png)

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
    `AND`/`OR`/`NOT`, quoted phrases, and one aggregation stage:
    `| stats count by <field>`.
  - **LogQL (subset)** — label selectors (`{level="ERROR"}`), line filters
    (`|=` contains, `|~` regex), and one aggregation stage:
    `count_over_time({...}[5m])` or `rate({...}[5m])`.
- Aggregating queries (`stats count by`, `count_over_time`, `rate`) render as
  a bar chart in place of the row table instead of a flat count.

### Dashboard

- Stat cards: total/enabled/disabled sources, reachable sources, log entries,
  errors, and warnings (last 24h).
- "Errors by file" bar chart (top files by error count, last 24h).
- Source activity table (entries/errors per source, live/enabled/status
  badges).
- Recent issues feed (latest errors & warnings across all sources).
- Same live auto-refresh and "new data available" banner as the Log Stream.

  ![dashboard ligh](/Screenshots/Screenshot_20260808_015211.png)
  ![dashboard dark](/Screenshots/Screenshot_20260808_091530.png)

### Source management (Sources screen)

- Register log sources of four types: a local file, a local directory (read
  non-recursively, capped to the 20 most recently modified files), an SFTP
  remote path, or a plain HTTP(S) URL.
- Edit, delete, and test connectivity (`UNVERIFIED` / `REACHABLE` /
  `UNREACHABLE`) for any source.
- **Enable / disable** a source — disabled sources are skipped by ingestion
  entirely (paused) but stay configured.
- **Live** toggle — live sources are re-read continuously (~2s) so the Log
  Stream and Dashboard update on their own; non-live sources are read once and
  frozen as a fixed snapshot until reloaded.
- **New data available** indicator — a non-live source whose underlying
  file(s) have changed since it was last read is flagged (per file, even
  inside a directory source), prompting a reload rather than silently going
  stale.

  ![log source light 2](/Screenshots/Screenshot_20260808_091334.png)
  ![log source light 1](/Screenshots/Screenshot_20260808_015315.png)
  ![log source dark 1](/Screenshots/Screenshot_20260808_091439.png)

### Shared Reload action

A Reload button (Log Stream and Dashboard) invalidates the ingestion cache so
non-live sources are re-read on demand — the deliberate counterpart to "live"
sources, which never need it.

## Stack

- **Backend**: Java 25, Spring Boot 4, Maven, Spring Data JPA, H2 (embedded,
  file-based), Flyway (schema migrations), Apache Lucene (embedded search
  index, `lucene-core`/`lucene-analysis-common`/`lucene-queryparser`/
  `lucene-facet`), sshj (SFTP)
- **Frontend**: TypeScript, React, Vite, plain CSS (dark mode by default,
  primary color `#447caa`, design tokens for spacing/radius/shadow in
  `theme.css`, self-hosted Barlow / Barlow Condensed type)

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

See [Known simplifications](#known-simplifications-first-phase) below for
what's deliberately still missing (per-user accounts, key-based SFTP auth,
host key verification, etc.).

## API

### Sources API

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/sources` | List all configured log sources |
| POST | `/api/sources` | Add a log source (`LOCAL_FILE`, `LOCAL_DIRECTORY`, `SFTP`, or `HTTP`) |
| PUT | `/api/sources/{id}` | Edit a source (resets its connectivity status) |
| DELETE | `/api/sources/{id}` | Remove a log source |
| POST | `/api/sources/{id}/test-connection` | Test whether a source is reachable |
| POST | `/api/sources/{id}/enable` \| `/disable` | Pause/resume ingestion for a source |
| POST | `/api/sources/{id}/enable-live` \| `/disable-live` | Toggle continuous re-reading for a source |

### Log Stream API

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/logs` | Filtered/sorted/paginated log entries (`search`, `level`, `source`, `file`, `rangeMinutes`, `sortBy`, `sortDir`, `page`, `size`) |
| GET | `/api/logs/files` | Distinct file names seen, optionally scoped to a `source` |
| POST | `/api/logs/reload` | Invalidate the ingestion cache so non-live sources are re-read |
| GET | `/api/logs/query` | Query-bar endpoint: indexed search against the durable Lucene store. Takes `q` and `queryLanguage` (`LUCENE`, `SPL`, or `LOGQL`) plus the same `source`/`file`/`rangeMinutes`/`sortBy`/`sortDir`/`page`/`size` params as above. Response is the same `LogQueryResult` shape with one addition: an `aggregation` field, populated (in place of `content`) when the query includes an aggregation stage (`stats count by`, `count_over_time`, `rate`) instead of returning rows. |

### Dashboard API

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/dashboard/summary` | Source/entry/error counts, errors-by-file, source activity, and recent issues (last 24h) |

## Known simplifications (first phase)

- Auth is a single shared admin account (HTTP Basic, see
  [Security](#security)) — no per-user accounts, roles, or audit log.
- SFTP host key verification is disabled (accepts any host key) to simplify
  connecting to ad-hoc dev servers.
- Directories are read non-recursively and capped to the 20 most recently
  modified files; there's no incremental/streaming tail (each read re-fetches
  the trailing window), and SFTP opens a fresh connection per read rather
  than pooling one.
- Log line parsing is heuristic on both ends: the backend's
  timestamp/level extraction (common timestamp formats, bracketed levels,
  Apache/Combined access-log style, BSD/RFC-3164 syslog) and the frontend's
  structured-format detection for the expanded row view (JSON, syslog,
  access log, logfmt) are both pattern-based, not a full grok/schema engine
  — neither recognizes every possible format, and CSV is not auto-detected.
- Change detection for the "new data available" indicator relies on file
  size/mtime (or an HTTP HEAD's `Content-Length`/`Last-Modified`), not a
  content hash, and is itself throttled (~5s) so it doesn't hammer a remote
  source on every poll.
- The SPL and LogQL query languages are deliberately **scoped subsets**, not
  full parity with real Splunk/Loki — see [Search &
  Query](#search--query) above for exactly what each supports. Anything
  outside that (subsearches, multi-stage pipelines, most LogQL/SPL
  functions, etc.) isn't recognized.
- Indexing is near-real-time, not instant: a source's content can take up to
  `SEARCH_INDEX_INTERVAL_MS` (default 5s) to appear in query-bar/indexed
  search results after it's read.
- Aggregation results are capped (top 100 groups for `stats count by`, up to
  500 time buckets for `count_over_time`/`rate`, defaulting to a 24h window
  when no explicit time range is given) rather than paginated — a query
  producing more groups/buckets than that is silently truncated to the cap.
- The per-document ID used for idempotent reindexing is derived from
  `source + file + sha256(timestamp + message)`; two genuinely
  byte-identical log lines from the same file at the same timestamp are
  disambiguated by their ordinal position within a single indexing batch,
  not tracked globally across batches.
- Search-index fingerprints (used to skip re-reading unchanged files) are
  held in memory only, not persisted — a backend restart costs one redundant
  (bounded, cheap) reindex pass per source rather than a correctness issue.

These are flagged for hardening in a later phase (per-user auth,
key-based SFTP auth, known-hosts verification, recursive/streaming
ingestion, etc.).

# <img src="frontend/public/terminal-svgrepo-com.svg" width="28" height="28" alt="" valign="middle" /> Logic

Log Analyzer — a full-stack app for registering log sources and inspecting
their content in real time.

This closes out the first phase: source management, real log ingestion with
filtering/sorting/pagination, a live-updating Log Stream, and a Dashboard
overview. Alerting is planned for a later phase.

## Features

### Log Stream

- Real ingestion: tails the trailing window of each source (bounded reads —
  512KB / 3,000 lines per file) rather than loading it whole, with basic
  level/timestamp/stack-trace-continuation parsing.
- Filtering by free-text search (debounced), source, file, severity
  (ERROR/WARN/INFO/DEBUG), and time range, plus one-click presets
  (all errors, errors & warnings, clear).
- Sortable columns (time, level, source, file) and backend-side pagination.
- Auto-refreshes on its own while any live source is in scope; shows a "●
  LIVE" indicator when doing so.
- A "new data available" banner (naming the exact file and source) appears
  above the table when a non-live source has changed, with a one-click
  Reload.

  ![log stream light](/Screenshots/Screenshot_20260808_015244.png)
  ![log stream dark](/Screenshots/Screenshot_20260808_091518.png)

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
  file-based), sshj (SFTP)
- **Frontend**: TypeScript, React, Vite, plain CSS (dark mode by default,
  primary color `#447caa`)

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
- Log line parsing is heuristic (common timestamp formats, bracketed levels,
  Apache/Combined access-log style) — it won't recognize every format.
- Change detection for the "new data available" indicator relies on file
  size/mtime (or an HTTP HEAD's `Content-Length`/`Last-Modified`), not a
  content hash, and is itself throttled (~5s) so it doesn't hammer a remote
  source on every poll.

These are flagged for hardening in a later phase (per-user auth,
key-based SFTP auth, known-hosts verification, recursive/streaming
ingestion, etc.).

# Logic

Log Analyzer — a full-stack app for registering and inspecting log sources.

This initial phase covers project setup: registering one or more log sources
(local files, local directories, or files on remote servers via SFTP or a
direct HTTP(S) URL), listing them, testing connectivity, and removing them.
Log parsing/searching/analysis comes in a later phase.

## Stack

- **Backend**: Java 25, Spring Boot 4, Maven, Spring Data JPA, H2 (embedded,
  file-based), sshj (SFTP)
- **Frontend**: TypeScript, React, Vite, plain CSS (dark mode by default,
  primary color `#447caa`)

## Running in dev

Backend (port 8080):

```
cd backend
mvn spring-boot:run
```

Frontend (port 5173):

```
cd frontend
npm install
npm run dev
```

The frontend dev server proxies `/api/*` requests to the backend, so no CORS
configuration changes are needed for normal dev use. Open
http://localhost:5173 in a browser.

## API

| Method | Path | Description |
|---|---|---|
| GET | `/api/sources` | List all configured log sources |
| POST | `/api/sources` | Add a log source (`LOCAL_FILE`, `LOCAL_DIRECTORY`, `SFTP`, or `HTTP`) |
| DELETE | `/api/sources/{id}` | Remove a log source |
| POST | `/api/sources/{id}/test-connection` | Test whether a source is reachable |

## Known simplifications (initial phase)

- SFTP passwords are stored as plaintext in the local H2 database file.
- SFTP host key verification is disabled (accepts any host key) to simplify
  connecting to ad-hoc dev servers.

Both are flagged for hardening in a later phase (encryption at rest / key-based
auth, and known-hosts verification, respectively).

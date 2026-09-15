![Cover](Screenshots/cover.png)

![license-badge](https://img.shields.io/badge/license-MIT-blue)

Lightweight log analyzer: register log sources, tail and index logs, run ad-hoc queries, and alert on patterns.

Quick links
- Features (detailed): [docs/features.md](docs/features.md)
- Run locally: see "Running in dev" below

Short summary

Logic is a single-container full-stack app (Spring Boot backend + React frontend) for ingesting, searching, and alerting on logs. It uses an embedded H2 database and an embedded Lucene index so no external services are required for a basic deployment.

Running in dev

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

The frontend dev server proxies `/api/*` to the backend. Open http://localhost:5173 (default dev credentials: `admin`/`admin`).

Deployment

```bash
docker compose up --build
```

This starts Logic on http://localhost:8080 and creates a named volume (`logic-data`) for durable state under `./data`. See `docker-compose.yml` for environment variables and example log-source mounts.

Desktop

See `desktop/README.md` for Tauri-based native Linux builds that bundle the backend jar.

Security & config

All security-relevant env vars and defaults remain in the original README's Security section — see `docs/features.md` for links to the related operational notes.

For full features, screenshots, and configuration details, read:
- [docs/features.md](docs/features.md)
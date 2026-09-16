# Roadmap Design Notes

Detailed designs for tickets referenced from [`roadmap.md`](roadmap.md) that
are too long to keep inline there. Each section corresponds to a ticket ID
(or ID range) in the roadmap's tier tables.

---

## Patterns drill-down follow-ups

LOGIC-107 (template mining, done) clusters repeated log lines into patterns
and answers "is this shape new / how often does it happen." What it doesn't
yet do is let you act on that signal the way a manual grep loop does: jump
from a spiking pattern to the actual lines, get paged when a genuinely new
shape shows up, or correct a cluster that merged (or split) badly. These are
the four follow-ups identified in review.

**Recommended order:** LOGIC-117 first — it's the one that actually replaces
the grep loop (see a pattern, see its lines), and it's a prerequisite for the
split half of LOGIC-119. LOGIC-118 and LOGIC-120 are independent and can land
in any order relative to the others.

### LOGIC-117 — Drill down from a pattern to its matching log lines

Patterns currently shows an aggregate count + one sample raw line per
template. There's no way to pivot from "this template spiked" to "show me
those lines" — `LogTemplate` keeps no per-line reference, only running
counts, and the Lucene index has no notion of which template a document
belongs to. This is the single biggest gap keeping Patterns from replacing a
manual grep loop.

**Design**

1. **Stamp a `templateId` onto every indexed document.** Today
   `TemplateMiningService.mine(source, file, entries)` only computes a
   per-*distinct-message* delta count and mines the positive delta (see its
   class comment for why — a reindex pass re-adds a file's whole tail
   window, not just new lines). Drill-down needs every entry tagged with its
   template regardless of whether that occurrence was newly counted. Split
   the existing lookup out of the counting logic: add
   `Map<String, Long> assignTemplates(LogSource source, Collection<String> distinctMessages)`
   that runs the same tokenize/mask/similarity-match against
   `clustersFor(source.getName())` for every distinct message text in the
   pass (creating a template for an unmatched one, exactly like today), and
   returns `message -> templateId`. `mine()` becomes a thin wrapper: compute
   `assignTemplates` once, do delta-counting against the returned ids, and
   hand the same map back to the caller.
2. **`SearchIndexService.indexEntries()`** calls `assignTemplates` once per
   (source, file) pass (right where it already calls `mine`), then passes
   each entry's assigned id into
   `documentBuilder.build(source, entry, docId, templateId)`.
3. **`LogDocumentBuilder`** adds `templateId` as a `StringField` (stored,
   exact-match filterable) + `SortedDocValuesField`, the same shape as the
   existing `source`/`file` fields — no new indexing pattern to invent.
4. **Query layer:** add an optional `templateId` filter to
   `LogQueryParams`, `LogQueryService`, and the Lucene query builder
   (identical treatment to the existing `source`/`file` exact-match
   filters), and thread it through `GET /api/logs` and the SSE stream's
   params.
5. **Frontend:** `fetchLogs`/`LogQueryParams` gain `templateId`; a "View
   matching lines" action on each Patterns row navigates to Log Stream
   pre-filtered to `source` + `templateId` (check `Dashboard.tsx`'s
   recent-issues click-through, if any, for an existing deep-link
   convention to mirror rather than inventing a new one). Log Stream shows a
   small "Filtered by pattern: `<template text>` ×" chip with a clear
   affordance when active.

**AC:** clicking "View matching lines" on a template with N occurrences
shows exactly those N lines in Log Stream, newest-first, further filterable
by the existing search/level controls.

**Effort:** M — touches indexing, the query layer, and two screens, but
every piece mirrors an existing filter mechanism (`source`/`file`) rather
than inventing new mechanics.

**Cost note:** assigning a template to every entry every pass costs more
than today's delta-only counting, but the comparison is per *distinct
message text* within a pass (already deduplicated), not per raw line — it
scales with shape cardinality, not line volume.

### LOGIC-118 — Alert on a newly appeared template

LOGIC-107's own motivating case — "notice a genuinely new error shape
without eyeballing" — isn't wired to alerting. `LogTemplate.firstSeenAt`
already exists; nothing watches it.

**Design**

- Add a new `AlertRuleType` value, e.g. `NEW_PATTERN`, alongside the
  existing `THRESHOLD`/`ANOMALY`. Unlike those, it needs no query/search/
  level fields — just the existing `source` scope field (required, since
  templates are always per-source) and a window.
- `AlertEvaluationService` gets a matching evaluation path: query
  `LogTemplateRepository.findBySource(source)` filtered to `firstSeenAt`
  falling inside the rule's evaluation window, fire an `AlertEvent` if any
  exist (or more than a configurable count, for noisy sources).
- Webhook payload includes the new template's text + sample line, reusing
  the existing generic `AlertEvent`/webhook delivery path — no new delivery
  mechanism needed.
- UI: `AlertRuleDialog` gains a "New pattern appeared" option; hides the
  query/level fields that don't apply to it, same way the dialog already
  branches between THRESHOLD and ANOMALY fields.

**AC:** a source that starts emitting a message shape never seen before
fires exactly one `AlertEvent` per newly appeared template per evaluation
window; templates that already existed before the rule was created don't
retroactively fire.

**Effort:** M. Independent of LOGIC-117.

### LOGIC-119 — Manual template management (delete now, split gated on LOGIC-117)

Mining is fully automatic. If two distinct events collide into one
over-generalized template, there's currently no manual escape hatch.

**Design**

- **Delete** (ship first): `DELETE /api/templates/{id}` removes the row and
  evicts it from `TemplateMiningService`'s in-memory per-source cluster
  cache (extend `invalidateCache()` to take an optional source, or just
  clear the whole cache the way `TemplateRetentionJob` already does — cheap
  at the expected call frequency of a manual admin action). Deleting a
  template does not retroactively reclassify already-counted history; the
  next occurrence of that shape simply mints a fresh template. Surface that
  distinction in the UI copy so it isn't mistaken for "hide this forever."
- **Split** (gate on LOGIC-117): before `templateId` is stamped per
  document, a template has no retained members to redistribute — there's
  nothing to split. Once LOGIC-117 ships, `POST /api/templates/{id}/split`
  can re-run `assignTemplates`-style clustering (with a stricter threshold,
  or the tokenizer's next-best alternate grouping) over just the documents
  currently tagged `templateId=X`, replacing that one template with several
  and re-stamping the affected documents.

**AC (delete, first phase):** deleting a template removes it from the
Patterns list; the next occurrence of that message shape creates a new
template rather than resurrecting the deleted one.

**Effort:** S (delete) / L (split — and only after LOGIC-117).

### LOGIC-120 — Tunable mining thresholds + load validation

The similarity threshold (fixed at 0.5) and the masking regex set are
hardcoded, and the ingest-path performance impact under real volume/shape
cardinality was flagged as an open scoping risk in LOGIC-107 and never
actually measured.

**Design**

- Promote `TemplateMiningService.SIMILARITY_THRESHOLD` to a config value
  (e.g. `app.template-mining.similarity-threshold`, default 0.5), following
  the existing `@Value`-per-constant pattern already used for retention/
  index-interval settings.
- Add a bulk-ingest benchmark (a throwaway script or a JMH-lite test)
  feeding a high-cardinality synthetic source (tens of thousands of lines,
  thousands of distinct shapes) through `SearchIndexService.reindexAll()`
  and measuring the mining pass's wall-clock and memory specifically,
  rather than guessing.
- Only if that benchmark shows a real problem: consider a cheaper
  first-level index for `clustersFor(source)` (e.g. hashing the first few
  masked tokens before the linear similarity scan) — don't build this
  speculatively ahead of a measured need.

**AC:** benchmark results are documented; if they surface a problem, a fix
ships alongside them — if not, the config exposure ships alone.

**Effort:** S (config) + S/M (benchmark, more only if it reveals a
problem).

---

## Exploratory: Security & Enterprise Hardening

These are ideas for making Logic appealing to larger/regulated teams and
more competitive with SaaS log platforms. As noted in `roadmap.md`, several
of these (RBAC, SSO, Vault/KMS, Helm/k8s, multi-node) conflict with the
project's current single-admin, self-hosted-desktop scope — treat this
section as raw material for a future scope decision, not a committed plan.
Original source material consolidated here for reference.

### LOGIC-121 — Secure defaults & hardening

- Stop falling back to a weak default admin password; require an explicit
  admin password on first run (or an interactive setup flow) and fail fast
  if unset in production mode.
- Ensure `app.auth.enabled` defaults to true; startup logs shouldn't imply
  insecure defaults are fine for production.
- Document/serve TLS termination; recommend TLS by default for remote
  installs.
- Add secure HTTP headers (HSTS, X-Content-Type-Options, Referrer-Policy,
  X-Frame-Options) and a strict CSP.
- **Repo pointer:** `backend/src/main/java/com/logic/analyzer/config/SecurityConfig.java`
  currently logs a warning and falls back to `admin/admin` — change here to
  require `app.admin.password`/`ADMIN_PASSWORD` and refuse to start without
  it outside dev mode. Consider `LOGIC_BOOTSTRAP_MODE=interactive|auto`.
- **Effort:** S/M

### LOGIC-122 — Pluggable auth & RBAC

- Pluggable authentication backends: JDBC-backed users, LDAP/AD, OIDC/SAML
  (Azure AD, Okta), or trusting a reverse-proxy (`X-Forwarded-User`) for
  private networks.
- RBAC with roles+scopes (Admin, Editor, Viewer, Integrations) enforced in
  both controllers (`@PreAuthorize`) and the UI (hide forbidden actions).
- Scoped, expiring API keys for integrations; optional TOTP MFA for admin
  accounts.
- **Repo pointer:** replace `InMemoryUserDetailsManager` in
  `SecurityConfig.java` with `JdbcUserDetailsManager` or a custom
  `UserDetailsService`. Add `spring-security-oauth2-client` (OIDC) and,
  if needed, `spring-security-saml2-service-provider` (SAML) to
  `backend/pom.xml`. Offer `app.auth.mode=basic|oidc|proxy|ldap`.
- **Effort:** L

### LOGIC-123 — Pluggable secrets/key management

- Move beyond a single base64 `ENCRYPTION_KEY` file: support external KMS
  (AWS/Azure/GCP), HashiCorp Vault, envelope encryption (per-item DEKs
  wrapped by a master key), and a key-rotation API.
- Ensure stored secrets (SFTP credentials, webhook secrets, integration
  tokens) are encrypted at rest and never written to logs.
- **Repo pointer:** `backend/src/main/java/com/logic/analyzer/crypto/EncryptionKeyProvider.java`
  currently reads `ENCRYPTION_KEY` or generates a file. Introduce a
  `KeyProvider { byte[] getMasterKey(); }` interface with implementations:
  `FileKeyProvider` (existing), `EnvKeyProvider`, `VaultKeyProvider`,
  `KmsKeyProvider` (decrypts a wrapped key on startup via the KMS API).
- **Effort:** L
- **Extends:** the existing `EncryptionKeyProvider` used for SFTP/HTTP
  credentials today.

### LOGIC-124 — Tamper-evident audit trail

- Extends LOGIC-110 (basic append-only audit table) with tamper-evidence
  and export: optionally hash-chain each audit row to the previous one,
  support exporting audit logs, and optional forwarding to an external SIEM
  (syslog/TLS, signed webhooks).
- **Repo pointer:** add a Flyway migration (e.g. `V6__create_admin_audit.sql`)
  with columns: id, created_at, actor, actor_ip, action_type, target_type,
  target_id, old_value (JSONB), new_value (JSONB), and optionally
  prev_row_hash/row_hash for the hash chain. Record entries from the service
  layer for create/update/delete on sources, alert rules, retention,
  redaction rules, and (if LOGIC-122 lands) user management. Read-only Audit
  screen in the UI.
- **Effort:** M
- **Depends on:** LOGIC-110

### LOGIC-125 — Data governance & privacy

- Extends LOGIC-106 (ingest-time redaction, done) with role-scoped field
  visibility (some roles see PII, others see masked data — depends on
  LOGIC-122's RBAC existing first), safe/sanitized export, on-demand GDPR
  erasure, and an optional WORM/immutable retention mode for compliance
  windows.
- **Effort:** L
- **Depends on:** LOGIC-106; role-scoped masking additionally depends on
  LOGIC-122.

### LOGIC-126 — Secure agent/ingest pipeline

- Authenticate collectors/agents to the server: short-lived join tokens
  plus mTLS, or signed agent binaries with a signed connection. Support key
  rotation and revocation.
- Limit ingestion surfaces: only allow defined sources to push logs;
  validate file/path attributes.
- Issue per-agent API tokens with limited scope and expiration, with a
  server-side revocation list.
- **Effort:** M/L

### LOGIC-127 — Hardened deployment & supply chain

- Hardened container images: non-root runtime user, minimal base
  (distroless/alpine), capability drops, explicit ports, signed images with
  published digests.
- Officially supported Helm chart / Kubernetes operator; SBOM generation and
  automatic image scanning in CI; documented air-gapped install path
  (zipped assets, offline installers).
- **Repo pointer:** harden the existing root `Dockerfile` and
  `docker-compose.yml`; add `charts/`/`k8s/` manifests alongside them.
- **Effort:** L

### LOGIC-128 — Observability integrations / APM-link

Recommendation: start with trace-ID correlation + links to *external* APM
tools rather than building a native trace store — faster to ship, far
smaller storage/ops burden, and keeps the codebase simpler for self-hosters.
Only pursue native trace ingestion if there's strong demonstrated demand.

**Phase 1 — Correlate & link (days–weeks)**
- Parse `trace_id`/`span_id` from structured logs (support common headers:
  `traceparent`, `baggage`, `x-datadog-trace-id`, `ot-tracer-traceid`) in
  `MessageFieldExtractor`/the ingestion pipeline.
- Admin settings for external APM base URL + templated trace links (e.g.
  Jaeger: `https://jaeger.example/trace/{{trace_id}}`), credentials stored
  encrypted via the existing `EncryptionKeyProvider` path.
- Frontend: a "Trace" column/context action on a log row linking out when a
  trace id is present.

**Phase 2 — Metrics/OTLP (weeks)**
- Prometheus metrics endpoint (Micrometer + Prometheus registry) and
  dashboard panels.
- Optionally accept OTLP/HTTP, or document running an OpenTelemetry
  Collector alongside Logic.

**Phase 3 — APM-lite (months, only if justified)**
- OTLP ingestion into a small sampled span store; a trace viewer correlating
  spans to logs by trace id (± time window); retention/sampling controls.

**Phase 4 — Full APM (long-term, likely out of scope)**
- Service maps, flamegraphs, distributed traces at scale — probably not
  worth the ops cost for this tool's target deployment size.

**Security, all phases:** encrypt trace payloads and integration secrets at
rest; apply the same redaction rules (LOGIC-106) to span attributes/tags;
recommend mTLS or one-time tokens for agents/collectors.

**Effort:** M (Phase 1) → L (Phase 3+, only if pursued)

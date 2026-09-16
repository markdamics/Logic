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

**Status:** all four follow-ups (LOGIC-117 through LOGIC-120) are done.

### LOGIC-117 — Drill down from a pattern to its matching log lines — done

Patterns previously showed only an aggregate count + one sample raw line per
template, with no way to pivot from "this template spiked" to "show me those
lines". Shipped as designed:

1. **`templateId` stamped onto every indexed document.**
   `TemplateMiningService.mine()` is now a thin wrapper around
   `assignTemplateEntities`, which tags every distinct message in a pass with
   its template regardless of whether that occurrence was newly counted;
   `assignTemplates(source, file, distinctMessages)` exposes the same lookup
   standalone (used by the LOGIC-119 split path once that lands).
2. **`SearchIndexService.indexEntries()`** mines once per (source, file) pass
   and passes each entry's assigned id into
   `documentBuilder.build(source, entry, docId, templateId)`.
3. **`LogDocumentBuilder`** adds `templateId` as a `StringField` (stored,
   exact-match filterable) + `SortedDocValuesField` — present only when
   mining assigned one, same shape as the existing `source`/`file` fields.
4. **Query layer:** `LogQueryParams`/`LogQueryService`/`QueryNode.scopeClauses`
   carry an optional `templateId` filter (identical treatment to `source`/
   `file`), threaded through `GET /api/logs` and the SSE stream's params.
5. **Frontend:** `fetchLogs`/`LogQueryParams` carry `templateId`; each
   Patterns row's "View matching lines" action navigates to Log Stream
   pre-filtered to `source` + `templateId`. Log Stream shows a dismissible
   "Filtered by pattern: `<template text>`" chip while active.

**AC:** clicking "View matching lines" on a template with N occurrences
shows exactly those N lines in Log Stream, newest-first, further filterable
by the existing search/level controls.

**Effort:** M — touched indexing, the query layer, and two screens, mirroring
the existing `source`/`file` filter mechanism throughout.

**Cost note:** assigning a template to every entry every pass costs more
than today's delta-only counting, but the comparison is per *distinct
message text* within a pass (already deduplicated), not per raw line — it
scales with shape cardinality, not line volume.

### LOGIC-118 — Alert on a newly appeared template — done

LOGIC-107's own motivating case — "notice a genuinely new error shape
without eyeballing" — is now wired to alerting.

- New `AlertRuleType.NEW_PATTERN`. Needs no query/search/level/metric
  fields — just the shared `source` scope field (required, validated in
  `AlertRuleService.validate()`) and `windowMinutes`; `AlertRuleDialog` hides
  the inapplicable fields the same way it already branches between THRESHOLD
  and ANOMALY.
- `AlertEvaluationService.evaluate()` branches to `evaluateNewPattern(rule)`
  instead of the count/bucket path. It queries
  `LogTemplateRepository.findBySourceAndFirstSeenAtAfter`/
  `findBySourceAndFileAndFirstSeenAtAfter` (new derived finders) with a
  cutoff of `max(now - windowMinutes, rule.getCreatedAt())` — the `createdAt`
  floor is what keeps pre-existing templates from retroactively firing, not
  the window alone. Each candidate template fires at most once ever, deduped
  via a new `AlertEvent.templateId` column and
  `AlertEventRepository.existsByAlertRuleIdAndTemplateId` — this replaces the
  in-memory `triggeredState` edge-trigger THRESHOLD/ANOMALY use, since a
  newly appeared template is a discrete one-shot event with no "resolve"
  side, not an ongoing condition to debounce.
- `AlertEvent` gained `templateId`/`templateText`/`sampleRawLine` columns
  (migration `V7__new_pattern_alert_event_fields.sql`), surfaced through
  `AlertEventResponse` and the Alerts screen's event history table. Webhook
  delivery reuses the existing signed-POST mechanism via a new
  `WebhookNotifier.notifyNewPatternAsync`/`sendNewPattern`, carrying
  `templateId`/`templateText`/`sampleRawLine` instead of a metric value.

**AC:** a source that starts emitting a message shape never seen before
fires exactly one `AlertEvent` per newly appeared template per evaluation
window; templates that already existed before the rule was created don't
retroactively fire.

**Effort:** M. Independent of LOGIC-117.

### LOGIC-119 — Manual template management (delete now, split unblocked by LOGIC-117) — done

Mining is fully automatic; this adds the manual escape hatch for when two
distinct events collide into one over-generalized template.

- **Delete:** `DELETE /api/templates/{id}` (`TemplateService.delete`) removes
  the row and calls `TemplateMiningService.invalidateCache()` — the same
  whole-cache-clear `TemplateRetentionJob` already uses for its bulk purge,
  cheap at the expected call frequency of a manual admin action. Does not
  retroactively reclassify already-counted history; the next occurrence of
  that shape simply mints a fresh template. The Patterns row's "Delete"
  button and its expanded-detail copy both say so, so it isn't mistaken for
  "hide this forever."
- **Split:** `POST /api/templates/{id}/split` (`TemplateService.split`)
  fetches the template's currently-indexed lines via `LogQueryService`
  scoped by LOGIC-117's `templateId` filter (capped at `MAX_SPLIT_SAMPLE` =
  5000, newest-first), then calls
  `TemplateMiningService.recluster(distinctMessages, threshold)` — a new
  method that groups messages using the same tokenize/similarity/merge logic
  as normal mining, but against a fresh, empty, unpersisted template tree
  and at a stricter threshold (0.85 vs. mining's normal 0.5) — isolated from
  this (source, file)'s real templates so the very template being split
  can't immediately re-absorb its own former members at 100% similarity. If
  reclustering yields only one group (nothing actually separates even at the
  stricter threshold) or no lines are currently indexed at all (they scrolled
  out of the tail window), it fails with a 400 rather than a silent no-op.
  Otherwise the original row is deleted and one new `LogTemplate` is created
  per group — `occurrenceCount`/`firstSeenAt`/`lastSeenAt`/`sampleRawLine`
  are a recount from the currently-indexed member lines, not a partition of
  the original historical aggregate (occurrences that already scrolled out
  of the window have no member lines left to redistribute) — followed by
  `SearchIndexService.forceReindexSource()` so the next scheduled pass
  re-stamps those lines' `templateId` against the new cluster set, rather
  than manually rewriting Lucene documents in place.

**AC (delete, first phase):** deleting a template removes it from the
Patterns list; the next occurrence of that message shape creates a new
template rather than resurrecting the deleted one.

**Effort:** S (delete) / L (split).

### LOGIC-120 — Tunable mining thresholds + load validation — done

The similarity threshold (fixed at 0.5) and the masking regex set were
hardcoded, and the ingest-path performance impact under real volume/shape
cardinality was flagged as an open scoping risk in LOGIC-107 and never
actually measured.

- `TemplateMiningService.SIMILARITY_THRESHOLD` is now a constructor-injected
  `@Value("${app.template-mining.similarity-threshold:0.5}")` field
  (`TEMPLATE_MINING_SIMILARITY_THRESHOLD` env override), same pattern as the
  existing retention/index-interval settings. The masking regex set
  (`TemplateTokenizer`) was left hardcoded - the benchmark below found no
  performance case for touching it, and it has no equivalent open design
  question the way the threshold did.
- `TemplateMiningBenchmarkTest` (`@Disabled`, manual - see its class
  javadoc) feeds `TemplateMiningService.mine()` a deliberately pathological
  synthetic load: every shape sharing the same token count, so the
  `tokenCount` pre-filter in `clustersFor()` never eliminates a candidate
  and every distinct message does a full linear similarity scan over every
  cluster minted so far. Results (wall-clock, JVM heap delta) across
  2,000-200,000 distinct shapes are recorded in `roadmap.md`'s LOGIC-120
  entry: realistic volumes (thousands of shapes, tens of thousands of
  lines) mine in under 2 seconds; the O(n²) scan cost only becomes visible
  north of ~100k fully-distinct, identically-sized shapes in a single pass
  - a scenario far outside what a real source would produce. No first-level
  index was built on top of `clustersFor()`, per the AC's "only if it
  reveals a problem" gate - it didn't.

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

# Logic Roadmap

Grounded in the current codebase (Java/Spring backend + React/TS frontend), not
a raw wishlist. This is a single-admin, self-hosted desktop log analyzer,
already with:

- Local/SFTP/HTTP tail ingestion (LogTailReader, TailSource implementations)
- Query engine with Lucene/SPL/LogQL query languages + saved searches
- Threshold + anomaly (std-dev) alerting with mute/events
- Push-based live tail (SSE), virtualized results table, client-side filtering
- Real-time rate/error histogram, log retention, PII redaction on ingest
- Pattern/template mining ("Patterns" view) for repeated log shapes
- HTTP Basic auth, single in-memory admin user, no RBAC by design

That last point matters: RBAC, SSO, multi-tenant auth, and other
enterprise/multi-node features assume a scale this tool isn't built for today.
They're tracked separately in [Exploratory: Security & Enterprise
Hardening](#exploratory-security--enterprise-hardening) rather than mixed into
the active tiers below, since adopting them would be a scope change, not just
a feature addition.

[`comparisons-with-datadog.md`](comparisons-with-datadog.md) identifies where
Logic is thin next to a full platform (ingestion breadth, workflow
integrations, governance). Each gap there was triaged by size: a
disproportionately large gap next to its value — matching Datadog's full
agent/integration library, native cross-platform trace/metrics correlation,
or its enterprise governance stack — is called out as out of scope rather
than turned into a ticket (the security/governance half is still recorded in
[Exploratory: Security & Enterprise
Hardening](#exploratory-security--enterprise-hardening) for future reference).
The two gaps that were small enough to actually be worth doing at this scale
became real tickets: LOGIC-129 (native alert channels) and LOGIC-130
(container log source).

## How to read this

- Tickets are grouped into priority tiers (P0 highest). Within a tier, work
  top to bottom.
- `done` tickets are kept for context (what shipped, and any deviation from
  plan) — see the linked "Shipped as" notes.
- Full step-by-step designs for tickets marked **[design →]** live in
  [`roadmap-designs.md`](roadmap-designs.md) to keep this file scannable.

## Quick reference

| ID | Title | Tier | Status | Effort |
|---|---|---|---|---|
| LOGIC-101 | SSE endpoint for live log | P0 | done | M/L |
| LOGIC-102 | Virtualized log table | P0 | done | M |
| LOGIC-103 | Client-side filter over live buffer | P0 | done | S |
| LOGIC-104 | Real-time rate/error histogram | P1 | done | M |
| LOGIC-105 | Log retention policy | P1 | done | S/M |
| LOGIC-106 | PII masking/redaction on ingest | P1 | done | M |
| LOGIC-107 | Pattern clustering (template mining) | P2 | done | L |
| LOGIC-109 | Cross-source correlation on a log line | P2 | open | M |
| LOGIC-110 | Admin action audit trail | P2 | open | S/M |
| LOGIC-117 | Drill down from pattern → matching lines | P2.1 | open | M |
| LOGIC-118 | Alert on a newly appeared template | P2.1 | open | M |
| LOGIC-119 | Manual template management (delete/split) | P2.1 | open | S/L |
| LOGIC-120 | Tunable mining thresholds + load validation | P2.1 | open | S + S/M |
| LOGIC-111 | Custom dashboard builder | P3 | open | L |
| LOGIC-112 | Ingest-time log sampling | P3 | open | M |
| LOGIC-113 | ML-based anomaly detection | P3 | open | L |
| LOGIC-114 | Automatic root-cause correlation | P3 | open | L |
| LOGIC-115 | Predictive capacity/failure analytics | P3 | open | L |
| LOGIC-116 | Public API for third-party integrations | P3 | open | M |
| LOGIC-130 | Container (Docker) log ingestion source | P3 | open | M |
| LOGIC-108 | Natural language → query language | P4 (deprioritized) | open | M/L |

---

## P0 — Real-time core

Fits the existing architecture, unlocks everything else. All shipped.

### LOGIC-101 — SSE endpoint for live log — done

Replaced 3s polling in LogStream.tsx with a push-based stream: `GET
/api/logs/stream` (SSE) backed by LogQueryService, EventSource on the
frontend with reconnect/backoff.

### LOGIC-102 — Virtualized log table — done

`@tanstack/react-virtual` in the results table; scrolls 10k+ buffered rows at
>50fps with a capped 5,000-line buffer. Depended on LOGIC-101.

### LOGIC-103 — Client-side filter over live buffer — done

Search/level filters apply as a pure client-side predicate over the SSE
buffer — no round-trip, no stream restart. Server-side filtering kept for
initial page load / non-live mode. Depended on LOGIC-101, LOGIC-102.

---

## P1 — High value, moderate effort

All shipped.

### LOGIC-104 — Real-time rate/error histogram — done

Canvas-based bucketed histogram fed by the SSE buffer, split by level,
rolling window. Depended on LOGIC-101.

### LOGIC-105 — Log retention policy — done

Configurable retention window + scheduled purge job
(`app.search.retention-days` / `app.search.purge-interval-ms`,
`RetentionPurgeJob`) already existed; this ticket added the missing half —
`SearchIndexStatsService` (index size on disk + oldest indexed entry) as two
new Dashboard stat cards.

### LOGIC-106 — PII masking/redaction on ingest — done

New `RedactionRule` entity/repository/service/controller (CRUD at
`/api/redaction/rules`, scoped like AlertRule's `source` field), a Flyway
migration, and a Redaction sidebar screen. `LogIngestionService` compiles
applicable rules once per source read and masks each message before a
`LogEntry` is constructed — raw value never reaches cache, index, or UI. Ships
with zero rules by default.

---

## P2 — Worth doing, bigger lift

### LOGIC-107 — Pattern clustering for noisy log lines (template mining) — done

Incremental streaming template miner (simplified Drain): tokenizes each
message, masks high-cardinality tokens, matches against a per-source template
tree within a similarity threshold. New `com.logic.analyzer.template`
package, `/api/templates`, a "Patterns" sidebar screen (table + trend
sparkline). Runs once per (source, file) reindex pass, diffing occurrence
deltas so reprocessing a tail window doesn't inflate counts. Retention piggybacks
on `app.search.retention-days`.

**Follow-up:** review flagged that this doesn't yet replace a manual grep
loop end-to-end — no drill-down from pattern to lines, no new-pattern
alerting, no manual merge/split, no threshold tuning. Those four gaps are
LOGIC-117 through LOGIC-120, tier **P2.1** below.

### LOGIC-109 — Cross-source correlation on a log line

No way today to see "what else happened around this event" across sources.

- From a selected LogEntry, query other sources/files within a small time
  window (e.g. ±5s) and surface related entries.
- **AC:** clicking a log row shows a "nearby events" panel with entries from
  other sources in the same window.
- **Effort:** M

### LOGIC-110 — Admin action audit trail

Single-admin model still benefits from an audit log for accountability
(config drift, who changed an alert rule and when).

- Log create/update/delete on LogSource, AlertRule (and retention/redaction
  settings) to an append-only audit table. Read-only view in the UI.
- **AC:** every source/alert-rule change is recorded with timestamp and
  old/new values; audit log itself isn't editable via the API.
- **Effort:** S/M
- **Note:** LOGIC-124 (exploratory) proposes hardening this into a
  tamper-evident/exportable trail — treat that as a later enhancement, not a
  blocker to shipping this basic version.
---

## P2.1 — Patterns drill-down follow-ups (closing the LOGIC-107 gap)

Recommended order: **117 first** (it's what actually replaces the grep loop,
and is a prerequisite for the split half of 119); 118 and 120 are independent
and can land in any order relative to the others. All depend on LOGIC-107
(done). Full designs: [`roadmap-designs.md`](roadmap-designs.md#patterns-drilldown-follow-ups).

### LOGIC-117 — Drill down from a pattern to its matching log lines **[design →]**

Stamp a `templateId` onto every indexed document so a Patterns row can link
to Log Stream pre-filtered to that pattern. Touches indexing, the query
layer, and two screens, but mirrors the existing `source`/`file` filter
mechanism throughout.

- **AC:** clicking "View matching lines" on a template with N occurrences
  shows exactly those N lines in Log Stream, newest-first, further
  filterable by existing controls.
- **Effort:** M

### LOGIC-118 — Alert on a newly appeared template **[design →]**

Wires LOGIC-107's own motivating case — notice a genuinely new error shape —
to alerting. New `AlertRuleType.NEW_PATTERN`, evaluated against
`LogTemplate.firstSeenAt` falling inside the rule's window.

- **AC:** a source emitting a never-seen message shape fires exactly one
  `AlertEvent` per newly appeared template per window; pre-existing templates
  don't retroactively fire.
- **Effort:** M

### LOGIC-119 — Manual template management (delete now, split gated on 117) **[design →]**

Delete ships first (`DELETE /api/templates/{id}`, evicts the miner's cache).
Split (`POST /api/templates/{id}/split`) needs LOGIC-117's per-document
`templateId` to have members to redistribute.

- **AC (delete phase):** deleting a template removes it from the Patterns
  list; the next occurrence of that shape mints a fresh template rather than
  resurrecting the deleted one.
- **Effort:** S (delete) / L (split, gated on LOGIC-117)

### LOGIC-120 — Tunable mining thresholds + load validation **[design →]**

The similarity threshold (fixed at 0.5) and masking regex set are hardcoded;
ingest-path performance under real volume was flagged in LOGIC-107 but never
measured.

- **AC:** benchmark results are documented; if they surface a problem, a fix
  ships alongside them — if not, the config exposure ships alone.
- **Effort:** S (config) + S/M (benchmark)

---

## P3 — From the feature survey, not yet scoped

### LOGIC-111 — Custom dashboard builder

Dashboard.tsx currently renders a single fixed layout; no way to add,
remove, or rearrange panels.

- Panel model (time-series, heatmap, pie/breakdown-by-level) backed by
  DashboardService, drag-and-drop layout persisted per admin.
- **AC:** adding/removing/reordering panels persists across reloads; existing
  overview ships as a default panel set.
- **Effort:** L

### LOGIC-112 — Ingest-time log sampling

LogIngestionService persists every entry; high-volume sources have no way to
cap storage/index growth short of full retention deletion.

- Configurable sampling strategy per LogSource (1-in-N, or rate-limit per
  level), applied before persistence/indexing. Default off; never silently
  drop ANOMALY/THRESHOLD-relevant entries.
- **AC:** a source at 1-in-10 sampling persists ~10% of lines; sampled-out
  count is visible on the Dashboard/source card.
- **Effort:** M

### LOGIC-113 — ML-based anomaly detection

AlertRule ANOMALY currently flags mean + k·stddev of prior windows; misses
seasonality/slow drift.

- Alternative anomaly model (EWMA or seasonal baseline) as a new AlertRule
  strategy, selectable alongside the existing std-dev baseline.
- **AC:** a rule using the new model correctly flags a seasonal spike a flat
  std-dev baseline would miss, on a fixture with known daily periodicity.
- **Effort:** L
- **Depends on:** LOGIC-105 (history to baseline against)

### LOGIC-114 — Automatic root-cause correlation

LOGIC-109 surfaces nearby events only on manual click; no automatic
"this alert is probably caused by that" signal.

- When an AlertRule fires, automatically query other sources/files in the
  same window and rank likely-related entries.
- **AC:** a fired alert event shows a ranked "likely related" list from other
  sources without manual interaction.
- **Effort:** L
- **Depends on:** LOGIC-109

### LOGIC-115 — Predictive capacity/failure analytics

No forward-looking view exists — Dashboard only summarizes the last 24h.

- Trend ingestion volume and error rate per source; project short-horizon
  storage growth and flag sources trending toward threshold/anomaly breach.
  Advisory only, not a blocking gate.
- **AC:** Dashboard shows a projected-storage-growth trend line; a source
  with rising error rate is flagged before crossing its alert threshold.
- **Effort:** L (needs scoping)
- **Depends on:** LOGIC-105

### LOGIC-116 — Public API for third-party integrations

Existing REST endpoints are consumed only by the bundled frontend.

- Formalize a versioned, read-only subset (log query, alert events) as a
  documented public API, distinct from internal frontend-only endpoints.
  Deliberately excludes a plugin/extension runtime.
- **AC:** documented endpoints are stable across releases and covered by
  contract tests separate from internal endpoint changes.
- **Effort:** M

### LOGIC-130 — Container (Docker) log ingestion source

`comparisons-with-datadog.md` calls out Datadog's ingestion breadth
(servers, containers, Kubernetes, cloud services, a large agent library) as a
gap. Matching that breadth wholesale is out of scope by size (see the top of
this doc) — a Docker-container `TailSource` is the one slice of it that's
actually proportionate to this tool: one new source type, reusing the
existing `LogSource`/`TailSource` abstraction, not a new ingestion platform.

- New `TailSource` implementation reading a container's stdout/stderr via
  the Docker Engine API (or `docker logs -f`-equivalent), registered like
  any other LogSource (name, parsing, redaction, retention all apply
  unchanged).
- **AC:** registering a running container as a source tails its stdout/stderr
  live through the existing SSE path, parsed and searchable exactly like a
  file-based source.
- **Effort:** M
- **Note:** Kubernetes pod log ingestion and cloud-provider log services
  (CloudWatch, GCP Logging) are a further, larger step down this path —
  intentionally not scoped here; revisit only if Docker-source usage shows
  real demand for it.

---

## P4 — Deprioritized (LLM-dependent)

### LOGIC-108 — Natural language → query language

Three query languages already exist (Lucene/SPL/LogQL); this would add a
plain-English entry point that compiles to one of them.

**Deprioritized:** unlike every other P2/P3 ticket, this one can't be scoped
or estimated without first picking an LLM provider/cost model — a decision
this project hasn't made and that sits awkwardly next to its
privacy/self-hosted positioning (see `comparisons-with-datadog.md`). It's
also more useful once Patterns (LOGIC-107/117) gives users a de-noised set of
shapes to query over, which is now shipped/in-flight. Revisit after P2/P2.1
lands and a provider decision is made.

- New endpoint or client-side call to translate an NL prompt into the
  currently-selected QueryLanguage, shown as an editable query before
  running (never auto-executed blind).
- **AC:** "show me errors from payments-api in the last hour" produces a
  correct, editable Lucene/SPL/LogQL query.
- **Effort:** M/L

---

## Exploratory: Security & Enterprise Hardening

This section captures ideas for making Logic viable for larger/regulated
teams (SSO, RBAC, KMS-backed secrets, audit hardening, hardened deployment,
APM/tracing integration). **They are not sequenced into the tiers above**
because several of them (multi-user RBAC, SSO, Vault/KMS, Helm/k8s, multi-node
scaling) directly contradict this project's current single-admin,
self-hosted-desktop scope statement at the top of this doc. Before any of
these move into an active tier, that scope decision needs to be made
explicitly — otherwise P0–P3 above and this section will pull the roadmap in
opposite directions.

Full detail (repo pointers, schema sketches, phased plans) is in
[`roadmap-designs.md`](roadmap-designs.md#exploratory-security--enterprise-hardening).

| ID | Title | Extends | Effort |
|---|---|---|---|
| LOGIC-121 | Secure defaults & hardening (no default creds, TLS docs, CSP/security headers) | — | S/M |
| LOGIC-122 | Pluggable auth & RBAC (JDBC/LDAP/OIDC/SAML, roles, API keys, MFA) | — | L |
| LOGIC-123 | Pluggable secrets/key management (KMS/Vault, envelope encryption, rotation) | `EncryptionKeyProvider` | L |
| LOGIC-124 | Tamper-evident audit trail (hash-chaining, export, SIEM forwarding) | LOGIC-110 | M |
| LOGIC-125 | Data governance & privacy (role-scoped field masking, GDPR export/erasure, WORM retention) | LOGIC-106 | L |
| LOGIC-126 | Secure agent/ingest pipeline (mTLS/signed agents, ingest rate limiting) | — | M/L |
| LOGIC-127 | Hardened deployment & supply chain (non-root images, SBOM, signed releases, Helm/k8s) | — | L |
| LOGIC-128 | Observability integrations / APM-link (trace-id correlation, OTel/Prometheus, phased toward APM-lite) | — | M → L |

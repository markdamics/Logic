# LOGIC-107 Follow-ups: Closing the Grep-Loop Gap

LOGIC-107 (template mining, done) clusters repeated log lines into patterns
and answers "is this shape new / how often does it happen." What it doesn't
yet do is let you act on that signal the way a manual grep loop does: jump
from a spiking pattern to the actual lines, get paged when a genuinely new
shape shows up, or correct a cluster that merged (or split) badly. This
doc plans the four follow-ups identified in review, referenced from
`logic-roadmap.md`.

**Recommended order:** LOGIC-117 first — it's the one that actually replaces
the grep loop (see a pattern, see its lines), and it's a prerequisite for the
split half of LOGIC-119. LOGIC-118 and LOGIC-120 are independent and can land
in any order relative to the others.

---

### LOGIC-117 — Drill down from a pattern to its matching log lines

Patterns currently shows an aggregate count + one sample raw line per
template. There's no way to pivot from "this template spiked" to "show me
those lines" — `LogTemplate` keeps no per-line reference, only running
counts, and the Lucene index has no notion of which template a document
belongs to. This is the single biggest gap keeping Patterns from replacing
a manual grep loop.

**Design**

1. **Stamp a `templateId` onto every indexed document.** Today
   `TemplateMiningService.mine(source, file, entries)` only computes a
   per-*distinct-message* delta count and mines the positive delta (see its
   class comment for why - a reindex pass re-adds a file's whole tail
   window, not just new lines). Drill-down needs every entry tagged with
   its template regardless of whether that occurrence was newly counted.
   Split the existing lookup out of the counting logic: add
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
   existing `source`/`file` fields - no new indexing pattern to invent.
4. **Query layer:** add an optional `templateId` filter to
   `LogQueryParams`, `LogQueryService`, and the Lucene query builder
   (identical treatment to the existing `source`/`file` exact-match
   filters), and thread it through `GET /api/logs` and the SSE stream's
   params.
5. **Frontend:** `fetchLogs`/`LogQueryParams` gain `templateId`; a "View
   matching lines" action on each Patterns row navigates to Log Stream
   pre-filtered to `source` + `templateId` (check `Dashboard.tsx`'s
   recent-issues click-through, if any, for an existing deep-link
   convention to mirror rather than inventing a new one). Log Stream shows
   a small "Filtered by pattern: `<template text>` ×" chip with a clear
   affordance when active.

**AC:** clicking "View matching lines" on a template with N occurrences
shows exactly those N lines in Log Stream, newest-first, further filterable
by the existing search/level controls.

**Effort:** M — touches indexing, the query layer, and two screens, but
every piece mirrors an existing filter mechanism (`source`/`file`) rather
than inventing new mechanics.

**Depends on:** LOGIC-107 (done).

**Cost note:** assigning a template to every entry every pass costs more
than today's delta-only counting, but the comparison is per *distinct
message text* within a pass (already deduplicated), not per raw line - it
scales with shape cardinality, not line volume.

---

### LOGIC-118 — Alert on a newly appeared template

LOGIC-107's own motivating case - "notice a genuinely new error shape
without eyeballing" - isn't wired to alerting. `LogTemplate.firstSeenAt`
already exists; nothing watches it.

**Design**

- Add a new `AlertRuleType` value, e.g. `NEW_PATTERN`, alongside the
  existing `THRESHOLD`/`ANOMALY`. Unlike those, it needs no
  query/search/level fields - just the existing `source` scope field
  (required, since templates are always per-source) and a window.
- `AlertEvaluationService` gets a matching evaluation path: query
  `LogTemplateRepository.findBySource(source)` filtered to `firstSeenAt`
  falling inside the rule's evaluation window, fire an `AlertEvent` if any
  exist (or more than a configurable count, for noisy sources).
- Webhook payload includes the new template's text + sample line, reusing
  the existing generic `AlertEvent`/webhook delivery path - no new delivery
  mechanism needed.
- UI: `AlertRuleDialog` gains a "New pattern appeared" option; hides the
  query/level fields that don't apply to it, same way the dialog already
  branches between THRESHOLD and ANOMALY fields.

**AC:** a source that starts emitting a message shape never seen before
fires exactly one `AlertEvent` per newly appeared template per evaluation
window; templates that already existed before the rule was created don't
retroactively fire.

**Effort:** M.

**Depends on:** LOGIC-107 (done). Independent of LOGIC-117.

---

### LOGIC-119 — Manual template management (delete now, split gated on LOGIC-117)

Mining is fully automatic. If two distinct events collide into one
over-generalized template, there's currently no manual escape hatch.

**Design**

- **Delete** (ship first): `DELETE /api/templates/{id}` removes the row and
  evicts it from `TemplateMiningService`'s in-memory per-source cluster
  cache (extend `invalidateCache()` to take an optional source, or just
  clear the whole cache the way `TemplateRetentionJob` already does - cheap
  at the expected call frequency of a manual admin action). Deleting a
  template does not retroactively reclassify already-counted history; the
  next occurrence of that shape simply mints a fresh template. Surface that
  distinction in the UI copy so it isn't mistaken for "hide this forever."
- **Split** (gate on LOGIC-117): before `templateId` is stamped per
  document, a template has no retained members to redistribute - there's
  nothing to split. Once LOGIC-117 ships, `POST /api/templates/{id}/split`
  can re-run `assignTemplates`-style clustering (with a stricter threshold,
  or the tokenizer's next-best alternate grouping) over just the documents
  currently tagged `templateId=X`, replacing that one template with several
  and re-stamping the affected documents.

**AC (delete, first phase):** deleting a template removes it from the
Patterns list; the next occurrence of that message shape creates a new
template rather than resurrecting the deleted one.

**Effort:** S (delete) / L (split - and only after LOGIC-117).

**Depends on:** LOGIC-107 (done); the split half additionally depends on
LOGIC-117.

---

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
  masked tokens before the linear similarity scan) - don't build this
  speculatively ahead of a measured need.

**AC:** benchmark results are documented; if they surface a problem, a fix
ships alongside them - if not, the config exposure ships alone.

**Effort:** S (config) + S/M (benchmark, more only if it reveals a
problem).

**Depends on:** LOGIC-107 (done).

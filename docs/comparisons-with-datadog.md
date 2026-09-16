Based on the scope in [README.md](README.md), Logic is best understood as a self-hosted log search, tailing, and alerting app for file-based sources, not a full Datadog-style observability platform.

Compared with Datadog on log analysis:

- Log ingestion
  - Logic: supports direct log source registration and live tailing with bounded reads, parsing of JSON/syslog/access logs/logfmt, and real-time stream updates.
  - Datadog: built for large-scale ingestion from servers, containers, Kubernetes, cloud services, and a huge library of agents/integrations.

- Search/query
  - Logic: offers Lucene, a SPL subset, and a LogQL subset, plus indexed structured fields and trace correlation. This is a strong lightweight query model.
  - Datadog: supports full Datadog LogQL and richer log processing features across massive datasets, with more advanced query semantics and platform integration.

- Alerting
  - Logic: includes threshold and anomaly alerts with webhook notifications and rule history, all in-app.
  - Datadog: enterprise-grade alerting, monitors, detection rules, dashboards, and workflow integrations across the broader platform.

- Observability integration
  - Logic: includes trace-id correlation and optional APM deep-linking, which is a nice touch, but it is not a full trace/metrics platform.
  - Datadog: native correlation across logs, traces, metrics, infrastructure, and services.

- Security and privacy
  - Logic: basic auth, encrypted secrets, redaction rules, and local data storage are all built in.
  - Datadog: stronger enterprise authentication, RBAC, governance, compliance, and managed security tooling.
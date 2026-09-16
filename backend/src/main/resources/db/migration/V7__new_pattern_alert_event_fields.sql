-- LOGIC-118: NEW_PATTERN alert rules fire per-LogTemplate instead of per-count,
-- so an alert_event needs somewhere to carry which template fired and its text/
-- sample line - nullable, since THRESHOLD/ANOMALY events never populate them.
-- template_id also doubles as AlertEvaluationService's dedup key (has this rule
-- already fired for this template?), hence the composite index below.
ALTER TABLE alert_event ADD COLUMN template_id BIGINT;
ALTER TABLE alert_event ADD COLUMN template_text VARCHAR(2000);
ALTER TABLE alert_event ADD COLUMN sample_raw_line VARCHAR(2000);

CREATE INDEX idx_alert_event_rule_template ON alert_event(alert_rule_id, template_id);

-- Backs the new findBySourceAndFirstSeenAtAfter/findBySourceAndFileAndFirstSeenAtAfter
-- queries NEW_PATTERN evaluation runs every pass.
CREATE INDEX idx_log_template_source_first_seen_at ON log_template(source, first_seen_at);

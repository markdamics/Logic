-- Pattern mining is opt-in per source (ingest-path cost), off by default -
-- toggled from the Sources screen the same way `live` already is.
ALTER TABLE log_source ADD COLUMN pattern_mining_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Templates are now scoped per (source, file), not just per source: a
-- directory source tailing several distinct services' log files shouldn't
-- cluster their shapes together just because they share a source. Nullable
-- since any template row created before this migration has no file on
-- record - it's simply never matched against again and ages out under
-- normal retention.
ALTER TABLE log_template ADD COLUMN file VARCHAR(255);

CREATE INDEX idx_log_template_source_file ON log_template(source, file);

-- LOGIC-119 split: a template created by breaking up an over-generalized one
-- keeps a text snapshot of what it was split from, so the Patterns UI can
-- badge it without needing to dereference the original row - that row is
-- deleted as part of the same split, so an id-only reference would point at
-- nothing.
ALTER TABLE log_template ADD COLUMN split_from_template_text VARCHAR(2000);

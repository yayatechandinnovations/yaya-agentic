-- M2-D: capabilities surface follow-up quick-reply chips after a successful
-- tool dispatch. Stored as a JSON array of strings.

ALTER TABLE capabilities
    ADD COLUMN follow_up_hints_json JSONB NOT NULL DEFAULT '[]'::jsonb;

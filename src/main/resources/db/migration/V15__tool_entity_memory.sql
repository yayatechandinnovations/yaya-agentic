-- M2.10 — operator-declared entity captures + bindings on every tool row.
-- See docs/design/entity-memory-design.md §5 ("ToolDescriptor extensions")
-- and §11 ("Code touch points"). Both columns store a JSON array — empty
-- by default so existing tools behave identically to pre-M2.10 (the engine
-- skips both phases when the lists are empty).

ALTER TABLE tools_registry
    ADD COLUMN captures_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN bindings_json JSONB NOT NULL DEFAULT '[]'::jsonb;

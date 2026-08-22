-- Owner cockpit (SL yaya-owner-ai-experience-v2-design.md):
--   tools.suggestions_json — per-tool result-aware suggestion chip rules
--     [{when,label,send}] evaluated by the engine after an OK dispatch.
--   capabilities.menu_json — optional persistent-menu block
--     {section,label,send,order,badgeSource} assembled per profile into the
--     "menu" ui_hint / session-start extension.

ALTER TABLE tools_registry
    ADD COLUMN IF NOT EXISTS suggestions_json jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE capabilities
    ADD COLUMN IF NOT EXISTS menu_json jsonb;

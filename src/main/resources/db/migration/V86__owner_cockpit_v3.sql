-- Owner AI Experience v3 (SL yaya-owner-ai-experience-v3-design.md):
--   GAP-1 §D3 — chat_inbox.actions_json: inline action buttons
--     [{label,send,style}] riding a proactive/outreach message through the
--     ingest → chat_inbox → InboxItem → drain-JSON path.
--   GAP-2 §D4 — tools_registry.table_outputs_json / card_fields_json:
--     declarative data-projection config the engine renders as data_table /
--     data_card UiHints from an OK tool result (mirrors the http-spec response
--     projection; persisted here for queryability).
-- NOTE: the platform tool table is `tools_registry` (NOT `tools`).

ALTER TABLE chat_inbox
    ADD COLUMN IF NOT EXISTS actions_json jsonb DEFAULT '[]'::jsonb;

ALTER TABLE tools_registry
    ADD COLUMN IF NOT EXISTS table_outputs_json jsonb;

ALTER TABLE tools_registry
    ADD COLUMN IF NOT EXISTS card_fields_json jsonb;

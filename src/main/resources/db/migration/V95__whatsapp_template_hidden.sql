-- An operator hides a template from the console lists: an older version
-- (sl_x_v1 once sl_x_v2 exists) or a test that WhatsApp approved and the
-- account therefore keeps. Display only: who may send the template and its
-- status stay as they are (whatsapp-shared-templates-design.md §10).
ALTER TABLE whatsapp_templates
    ADD COLUMN hidden_at TIMESTAMPTZ,
    ADD COLUMN hidden_by VARCHAR(128);

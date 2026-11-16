-- whatsapp-shared-templates-design.md §3 — one template per WhatsApp Business
-- Account (= the Twilio account a sender speaks through), shared by the
-- workspaces that hold it. A template row stops belonging to a sender.

ALTER TABLE whatsapp_templates
    ADD COLUMN account_key       VARCHAR(64),
    ADD COLUMN created_by_tenant VARCHAR(64);

-- §3.2 step 1 — the account is the sender's messaging account, or the platform's.
UPDATE whatsapp_templates t
   SET account_key       = COALESCE(s.messaging_account_id, 'platform'),
       created_by_tenant = t.tenant_id
  FROM whatsapp_senders s
 WHERE s.id = t.sender_id;

CREATE TABLE whatsapp_template_holders (
    template_id  BIGINT       NOT NULL REFERENCES whatsapp_templates(id) ON DELETE CASCADE,
    tenant_id    VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    granted_by   VARCHAR(128) NOT NULL,
    granted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, tenant_id)
);
CREATE INDEX whatsapp_template_holders_tenant_idx ON whatsapp_template_holders(tenant_id);

-- §3.2 step 2 — one row survives per (account, name, locale): the most
-- advanced status, then the oldest.
CREATE TEMPORARY TABLE whatsapp_templates_keep ON COMMIT DROP AS
SELECT DISTINCT ON (account_key, name, locale)
       id AS keep_id, account_key, name, locale,
       content_hash, body, category, header_kind, buttons_json
  FROM whatsapp_templates
 ORDER BY account_key, name, locale,
          CASE status WHEN 'APPROVED' THEN 0 WHEN 'SUBMITTED' THEN 1 WHEN 'PAUSED' THEN 2
                      WHEN 'REJECTED' THEN 3 ELSE 4 END,
          id;

-- §3.2 step 3 — every workspace whose copy has the surviving content holds it.
INSERT INTO whatsapp_template_holders (template_id, tenant_id, granted_by)
SELECT DISTINCT k.keep_id, t.tenant_id, 'migration-v94'
  FROM whatsapp_templates t
  JOIN whatsapp_templates_keep k
    ON k.account_key = t.account_key AND k.name = t.name AND k.locale = t.locale
 WHERE t.id = k.keep_id
    OR (    t.content_hash IS NOT DISTINCT FROM k.content_hash
        AND t.body = k.body
        AND t.category = k.category
        AND t.header_kind = k.header_kind
        AND COALESCE(t.buttons_json, '') = COALESCE(k.buttons_json, ''));

-- §3.2 step 4 — the other copies go; a copy with different content could
-- never have been approved under that name in the same account.
DELETE FROM whatsapp_templates t
 USING whatsapp_templates_keep k
 WHERE k.account_key = t.account_key AND k.name = t.name AND k.locale = t.locale
   AND t.id <> k.keep_id;

DROP INDEX IF EXISTS whatsapp_templates_tenant_idx;
ALTER TABLE whatsapp_templates
    DROP CONSTRAINT whatsapp_templates_uniq,
    DROP COLUMN tenant_id,
    DROP COLUMN sender_id,
    ALTER COLUMN account_key SET NOT NULL,
    ADD CONSTRAINT whatsapp_templates_uniq UNIQUE (account_key, name, locale);

-- Who shared what with whom, kept after the holder row or the template is gone.
CREATE TABLE whatsapp_template_holder_events (
    id             BIGSERIAL    PRIMARY KEY,
    template_id    BIGINT       NOT NULL,
    account_key    VARCHAR(64)  NOT NULL,
    template_name  VARCHAR(128) NOT NULL,
    locale         VARCHAR(16)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,
    action         VARCHAR(16)  NOT NULL CHECK (action IN ('SHARED','UNSHARED')),
    actor          VARCHAR(128) NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX whatsapp_template_holder_events_template_idx
    ON whatsapp_template_holder_events(template_id, occurred_at);

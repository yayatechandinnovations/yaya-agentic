-- whatsapp-sender-transfer-design.md §7 — move a live WhatsApp sender to another
-- workspace without a new registration.

-- A transferred sender's row stays in the source workspace as history; Twilio may
-- still post to its id (queued webhooks, a re-point that hasn't landed), so the
-- webhook forwards those to the successor (§6.3).
ALTER TABLE whatsapp_senders
    ADD COLUMN transferred_to_sender_id VARCHAR(64) REFERENCES whatsapp_senders(id);

-- One row per transfer: the audit trail and the webhook re-point retry queue
-- (§6.4). No FK to tenants or senders — the trail outlives an archived workspace.
CREATE TABLE whatsapp_sender_transfers (
    id                  VARCHAR(64)  PRIMARY KEY,
    phone_e164          VARCHAR(20)  NOT NULL,
    twilio_sender_sid   VARCHAR(64)  NOT NULL,
    source_tenant_id    VARCHAR(64)  NOT NULL,
    source_sender_id    VARCHAR(64)  NOT NULL,
    dest_tenant_id      VARCHAR(64)  NOT NULL,
    dest_sender_id      VARCHAR(64)  NOT NULL,
    effects_json        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    repoint_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    repoint_attempts    INT          NOT NULL DEFAULT 0,
    repoint_last_error  TEXT,
    requested_by        VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    repointed_at        TIMESTAMPTZ,
    CONSTRAINT whatsapp_sender_transfers_repoint_chk
        CHECK (repoint_status IN ('PENDING','DONE','FAILED'))
);
CREATE INDEX whatsapp_sender_transfers_pending_idx
    ON whatsapp_sender_transfers(created_at) WHERE repoint_status = 'PENDING';
CREATE INDEX whatsapp_sender_transfers_dest_idx
    ON whatsapp_sender_transfers(dest_sender_id);

-- §10.2 — same reasoning as V92 for WhatsApp: a revoked SMS number keeps its row
-- as history but must not lock the number forever.
ALTER TABLE sms_senders DROP CONSTRAINT sms_senders_phone_uniq;
CREATE UNIQUE INDEX sms_senders_live_phone_uniq
    ON sms_senders(phone_e164)
    WHERE status <> 'REVOKED';

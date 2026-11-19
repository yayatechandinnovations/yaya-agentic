-- «Enviar ticket por WhatsApp» platform deltas (smart-lottery
-- pos-ticket-whatsapp-share-design.md §8, P4 + P5).

-- P4: a binding may answer an unknown caller with a fixed line instead of
-- silence (AUTO_REPLY); the line is per binding, in the tenant's own words.
ALTER TABLE channel_bindings
    DROP CONSTRAINT IF EXISTS channel_bindings_on_unresolved_chk;

ALTER TABLE channel_bindings
    ADD CONSTRAINT channel_bindings_on_unresolved_chk
        CHECK (on_unresolved IN ('REJECT','ANONYMOUS','AUTO_REPLY'));

ALTER TABLE channel_bindings
    ADD COLUMN IF NOT EXISTS unresolved_reply_text TEXT;

-- When the last auto-reply went to a phone on a sender: at most one per 24 h,
-- so a chatty unknown caller gets the line once, not on every message.
CREATE TABLE IF NOT EXISTS channel_auto_replies (
    sender_id        VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    end_user_phone   VARCHAR(20)  NOT NULL,
    replied_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sender_id, end_user_phone)
);

-- P5: deterministic keyword actions on a sender. An inbound whose normalised
-- text starts with the prefix is forwarded to the tenant URL (signed with the
-- tenant's service-token secret) and the returned reply is sent back — no
-- engine run, no session.
CREATE TABLE IF NOT EXISTS whatsapp_keyword_actions (
    id           BIGSERIAL     PRIMARY KEY,
    tenant_id    VARCHAR(64)   NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sender_id    VARCHAR(64)   NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    prefix       VARCHAR(64)   NOT NULL,
    forward_url  VARCHAR(1024) NOT NULL,
    enabled      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT whatsapp_keyword_actions_uniq UNIQUE (sender_id, prefix)
);

CREATE INDEX IF NOT EXISTS whatsapp_keyword_actions_sender_idx
    ON whatsapp_keyword_actions (tenant_id, sender_id);

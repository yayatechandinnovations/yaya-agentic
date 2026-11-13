-- WhatsApp ops channel (smart-lottery-v1 docs/whatsapp-ops-integration-design.md §6, §14):
-- bound action buttons on proactive messages, per-recipient delivery tracking,
-- the system sender's WhatsApp leg, and declarative (header + buttons) templates.

-- §6.4 — every action a proactive message offers. The id is a 128-bit random
-- capability: single-use, expiring, bound to the identity it was delivered to.
CREATE TABLE outreach_actions (
    id                VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    outreach_id       VARCHAR(64)  NOT NULL,
    position          INT          NOT NULL,
    kind              VARCHAR(8)   NOT NULL,
    label             VARCHAR(64)  NOT NULL,
    style             VARCHAR(16)  NOT NULL DEFAULT 'neutral',
    send_text         TEXT,
    tool_id           VARCHAR(128),
    args_json         TEXT,
    ask_json          TEXT,
    owner_subject     VARCHAR(255),
    recipient_phone   VARCHAR(20),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    consumed_at       TIMESTAMPTZ,
    consumed_by       VARCHAR(255),
    result_json       TEXT,
    CONSTRAINT outreach_actions_kind_chk CHECK (kind IN ('TOOL','SEND')),
    CONSTRAINT outreach_actions_tool_chk CHECK (kind <> 'TOOL' OR tool_id IS NOT NULL)
);

CREATE INDEX outreach_actions_outreach_idx ON outreach_actions(outreach_id, position);
CREATE INDEX outreach_actions_expires_idx ON outreach_actions(expires_at);

-- §6.2 / §14.2 — how each resolved target of an ingest actually got the message.
CREATE TABLE outreach_deliveries (
    id                    BIGSERIAL    PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    outreach_id           VARCHAR(64)  NOT NULL,
    recipient_key         VARCHAR(255) NOT NULL,
    channel               VARCHAR(16)  NOT NULL,
    whatsapp_sender_id    VARCHAR(64),
    end_user_phone        VARCHAR(20),
    template_name         VARCHAR(128),
    provider_message_sid  VARCHAR(64),
    status                VARCHAR(24)  NOT NULL,
    status_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    fallback_reason       VARCHAR(48),
    failure_code          VARCHAR(64),
    body                  TEXT,
    seeded_session_id     VARCHAR(64),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT outreach_deliveries_channel_chk CHECK (channel IN ('CHAT','WHATSAPP')),
    CONSTRAINT outreach_deliveries_status_chk
        CHECK (status IN ('QUEUED','SENT','DELIVERED','READ','FAILED','CHAT_DELIVERED'))
);

CREATE INDEX outreach_deliveries_outreach_idx ON outreach_deliveries(outreach_id);
CREATE INDEX outreach_deliveries_sid_idx ON outreach_deliveries(provider_message_sid);
CREATE INDEX outreach_deliveries_phone_idx
    ON outreach_deliveries(whatsapp_sender_id, end_user_phone, created_at DESC);

-- §6.2 — the WhatsApp number a system sender delivers through (null = chat only).
ALTER TABLE system_senders
    ADD COLUMN whatsapp_sender_id VARCHAR(64) REFERENCES whatsapp_senders(id) ON DELETE SET NULL;

-- §6.3 — declarative templates: media header, quick-reply buttons, approval samples.
ALTER TABLE whatsapp_templates
    ADD COLUMN header_kind   VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN buttons_json  TEXT,
    ADD COLUMN samples_json  TEXT,
    ADD COLUMN content_hash  VARCHAR(64),
    ADD CONSTRAINT whatsapp_templates_header_chk CHECK (header_kind IN ('NONE','DOCUMENT','IMAGE'));

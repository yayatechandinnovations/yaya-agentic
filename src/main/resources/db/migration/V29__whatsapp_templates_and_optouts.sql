-- W6 — Templates registry + opt-out roster per design §11.

CREATE TABLE whatsapp_templates (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sender_id         VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    name              VARCHAR(128) NOT NULL,
    locale            VARCHAR(16)  NOT NULL,
    category          VARCHAR(32)  NOT NULL,
    body              TEXT         NOT NULL,
    placeholder_count INT          NOT NULL DEFAULT 0,
    bsp_template_id   VARCHAR(128),
    bsp_content_sid   VARCHAR(64),     -- Twilio's ContentSid once registered
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    rejection_reason  TEXT,
    submitted_at      TIMESTAMPTZ,
    approved_at       TIMESTAMPTZ,
    last_sync_at      TIMESTAMPTZ,
    CONSTRAINT whatsapp_templates_category_chk
        CHECK (category IN ('MARKETING','UTILITY','AUTHENTICATION')),
    CONSTRAINT whatsapp_templates_status_chk
        CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAUSED')),
    CONSTRAINT whatsapp_templates_uniq UNIQUE (sender_id, name, locale)
);

CREATE INDEX whatsapp_templates_tenant_idx ON whatsapp_templates(tenant_id, sender_id);

CREATE TABLE channel_opt_outs (
    sender_id         VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    end_user_phone    VARCHAR(20)  NOT NULL,
    opted_out_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reason            VARCHAR(64),
    PRIMARY KEY (sender_id, end_user_phone)
);

CREATE INDEX channel_opt_outs_phone_idx ON channel_opt_outs(end_user_phone);

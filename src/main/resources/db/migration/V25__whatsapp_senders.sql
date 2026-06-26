-- W1 — first of the WhatsApp-channel migrations. Lands ONLY the sender
-- aggregate; the rest of the channel surface (bindings, sessions, inbound /
-- outbound, templates, opt-outs) arrives in later milestone migrations so
-- each W-milestone has a clean self-contained data delta.
--
-- Note on version number: design `whatsapp-channel-design.md` §13 calls this
-- V23, but V22's preamble reserves V23 + V24 for the tenants UUID PK
-- cutover. We slot in at V25 to leave that runway untouched; downstream
-- channel migrations continue from there.
--
-- The tenant_id FK matches every other child table in the schema today
-- (VARCHAR(64) → tenants(id)). When the PK swap eventually ships, all
-- channel tables migrate alongside the rest of the codebase in one pass —
-- not piecemeal.

CREATE TABLE whatsapp_senders (
    id                       VARCHAR(64)  PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_e164               VARCHAR(20)  NOT NULL,
    display_name             VARCHAR(128) NOT NULL,
    approved_display_name    VARCHAR(128),
    business_profile_json    JSONB        NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    twilio_sender_sid        VARCHAR(64),
    onboarding_state_json    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    webhook_secret_enc       BYTEA        NOT NULL,
    default_locale           VARCHAR(16)  NOT NULL DEFAULT 'en-US',
    metadata_json            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at             TIMESTAMPTZ,
    CONSTRAINT whatsapp_senders_phone_uniq UNIQUE (phone_e164),
    CONSTRAINT whatsapp_senders_status_chk
        CHECK (status IN ('DRAFT','SUBMITTING','PENDING_REVIEW','ACTIVE',
                          'SUSPENDED','FAILED','REVOKED'))
);

CREATE INDEX whatsapp_senders_tenant_idx ON whatsapp_senders(tenant_id, status);

-- System messaging — trusted integration senders & agent-mediated delivery
-- (docs/design/system-messaging-design.md, milestone SM-0). An external app
-- (POS/ERP/back-office) becomes a trusted participant that injects text
-- messages into a user's chat, delivered store-and-forward.

-- A registered integration sender. Delivery is scoped to one chat entry point
-- (the "chat the user added"); trust/mediation/addressing are its config.
CREATE TABLE system_senders (
    id                   VARCHAR(64)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    entry_point_id       VARCHAR(64)  NOT NULL,
    display_name         VARCHAR(120) NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT false,
    -- SenderTrust: which Authenticator proves the app, required scopes, credential handle.
    trust_json           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    mediation            VARCHAR(16)  NOT NULL DEFAULT 'VERBATIM',
    -- Response tool (SM-2) — carries the user's reply back to the originator. Nullable in SM-0.
    response_tool_id     VARCHAR(128),
    -- AddressingPolicy: who this sender may target (§6).
    addressing_json      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- CapabilityMask (SM-2, §9.1), MediaPolicy (SM-1, §10), RateAndQuiet (SM-3). Nullable until wired.
    capability_mask_json JSONB,
    media_policy_json    JSONB,
    limits_json          JSONB,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT system_senders_mediation_chk
        CHECK (mediation IN ('VERBATIM','LLM_MEDIATED'))
);
CREATE INDEX system_senders_tenant_idx ON system_senders(tenant_id);

-- Ingest log + idempotency ledger. One row per accepted (or dropped) inbound
-- message; unique dedup key makes at-least-once integrations safe (§16 #10).
CREATE TABLE system_inbound_messages (
    id               VARCHAR(64)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    sender_id        VARCHAR(64)  NOT NULL,
    dedup_key        VARCHAR(200) NOT NULL,
    recipient_json   JSONB        NOT NULL,
    session_id       VARCHAR(64),
    body             TEXT,
    correlation_json JSONB,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DELIVERED',
    metadata_json    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT system_inbound_messages_status_chk
        CHECK (status IN ('RECEIVED','DELIVERED','FAILED','DROPPED')),
    CONSTRAINT system_inbound_messages_dedup_uniq UNIQUE (tenant_id, sender_id, dedup_key)
);
CREATE INDEX system_inbound_messages_sender_idx
    ON system_inbound_messages(tenant_id, sender_id, received_at DESC);

-- Durable identity→session index: the operator's outreach session per
-- (tenant, entry point, recipient key). Lets repeat messages to the same
-- recipient reuse one coherent thread (§6, delivery-target decision).
CREATE TABLE outreach_sessions (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    entry_point_id  VARCHAR(64)  NOT NULL,
    recipient_key   VARCHAR(255) NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    profile_id      VARCHAR(128) NOT NULL,
    profile_version INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT outreach_sessions_uniq UNIQUE (tenant_id, entry_point_id, recipient_key)
);
CREATE INDEX outreach_sessions_session_idx ON outreach_sessions(session_id);

-- Store-and-forward inbox for chat delivery (§11): the message exists the
-- moment ingest returns; the operator drains undelivered rows on next open.
-- body is the delivery copy; the recorded Turn is the audit source of truth.
CREATE TABLE chat_inbox (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    entry_point_id   VARCHAR(64)  NOT NULL,
    recipient_key    VARCHAR(255) NOT NULL,
    session_id       VARCHAR(64)  NOT NULL,
    turn_id          VARCHAR(64),
    outreach_id      VARCHAR(64),
    sender_display   VARCHAR(120),
    subject          VARCHAR(200),
    body             TEXT,
    correlation_json JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at     TIMESTAMPTZ
);
-- Hot path: undelivered rows for a recipient, oldest first.
CREATE INDEX chat_inbox_pending_idx
    ON chat_inbox(tenant_id, entry_point_id, recipient_key, created_at ASC)
    WHERE delivered_at IS NULL;

-- SM-3: role addressing + push wake (system-messaging-design.md §6, §11).

-- Role roster: which verified subjects belong to a role, so a ByRole message
-- fans out to each member's chat (a shift-wide closure report). v1 is a
-- platform-managed roster (open question §18.3); claim-derived membership can
-- layer on later.
CREATE TABLE role_members (
    tenant_id  VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    role       VARCHAR(128) NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, role, subject)
);
CREATE INDEX role_members_lookup_idx ON role_members(tenant_id, role);

-- Device push tokens for content-free wake nudges (§11 #3). The inbox row is
-- the source of truth; push only optimizes latency.
CREATE TABLE chat_push_tokens (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    entry_point_id VARCHAR(64)  NOT NULL,
    recipient_key  VARCHAR(255) NOT NULL,
    platform       VARCHAR(16)  NOT NULL DEFAULT 'WEBPUSH',
    token          TEXT         NOT NULL,
    endpoint_json  JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chat_push_tokens_platform_chk
        CHECK (platform IN ('WEBPUSH','FCM','APNS')),
    CONSTRAINT chat_push_tokens_uniq UNIQUE (recipient_key, token)
);
CREATE INDEX chat_push_tokens_recipient_idx
    ON chat_push_tokens(tenant_id, entry_point_id, recipient_key);

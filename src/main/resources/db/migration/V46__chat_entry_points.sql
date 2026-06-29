-- Chat channel — branded, publicly addressable entry points (chat-channel-design.md §10).
-- A chat entry point is the Chat-channel analog of a WhatsApp/SMS sender: it pins one
-- tenant profile, carries branding (the LOGO | NAME tile), an auth mode, and an opaque
-- public handle encoded in the QR / deep link. Unlike senders there is no BSP, no
-- regulatory state machine, and no inbound dedup / outbound buffer — chat is synchronous
-- and streams over the existing SSE wire.

CREATE TABLE chat_entry_points (
    id                 VARCHAR(64)  PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    handle             VARCHAR(64)  NOT NULL,
    profile_id         VARCHAR(128) NOT NULL,
    profile_version    INT,
    display_name       VARCHAR(120) NOT NULL,
    logo_url           TEXT         NOT NULL,
    accent_color_hex   VARCHAR(9),
    welcome_text       TEXT,
    default_locale     VARCHAR(16)  NOT NULL DEFAULT 'en-US',
    auth_mode          VARCHAR(24)  NOT NULL DEFAULT 'ANONYMOUS_ONLY',
    auth_config_json   JSONB        NOT NULL DEFAULT '{"kind":"None"}'::jsonb,
    abuse_policy_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    session_policy_json JSONB       NOT NULL,
    status             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chat_entry_points_handle_uniq UNIQUE (handle),
    CONSTRAINT chat_entry_points_auth_mode_chk
        CHECK (auth_mode IN ('ANONYMOUS_ONLY','AUTH_REQUIRED','AUTH_OPTIONAL')),
    CONSTRAINT chat_entry_points_status_chk
        CHECK (status IN ('DRAFT','ACTIVE','PAUSED','DEACTIVATED'))
);

-- The handle is the public capability looked up on every front-door hit; it must be
-- unique platform-wide (it carries no tenant scope of its own — §6.1).
CREATE INDEX chat_entry_points_tenant_idx ON chat_entry_points(tenant_id, status);

-- Wire CHAT_ENTRY_POINT into the shared channel binding surface so a MatchAny binding
-- can pin the entry point to its profile (ChannelProfileResolver resolves it the same
-- way it resolves a WhatsApp sender).
ALTER TABLE channel_bindings DROP CONSTRAINT IF EXISTS channel_bindings_endpoint_kind_chk;
ALTER TABLE channel_bindings ADD CONSTRAINT channel_bindings_endpoint_kind_chk
    CHECK (endpoint_kind IN ('WHATSAPP_SENDER','SMS_SENDER','CHAT_ENTRY_POINT'));

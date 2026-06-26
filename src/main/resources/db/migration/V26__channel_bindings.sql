-- W3 — channel_bindings: maps a channel endpoint to a profile per
-- whatsapp-channel-design.md §8. One sender can have multiple bindings
-- with different RoutingRules; lowest priority wins on a given inbound.
--
-- endpoint_kind / endpoint_id is a polymorphic FK (no real DB FK because
-- the SPI admits multiple endpoint kinds in the future — SmsNumberRef,
-- TelegramBotRef, …). The service layer enforces tenant alignment between
-- the binding row and the referenced endpoint.

CREATE TABLE channel_bindings (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    endpoint_kind       VARCHAR(32)  NOT NULL,
    endpoint_id         VARCHAR(64)  NOT NULL,
    profile_id          VARCHAR(128) NOT NULL,
    profile_version     INT,
    routing_json        JSONB        NOT NULL DEFAULT '{"kind":"MatchAny"}'::jsonb,
    session_policy_json JSONB        NOT NULL,
    priority            INT          NOT NULL DEFAULT 100,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deactivated_at      TIMESTAMPTZ,
    CONSTRAINT channel_bindings_endpoint_kind_chk
        CHECK (endpoint_kind IN ('WHATSAPP_SENDER')),
    CONSTRAINT channel_bindings_status_chk
        CHECK (status IN ('ACTIVE','PAUSED','DEACTIVATED'))
);

-- §8.1: bindings looked up by (tenant, endpoint) in priority order on the
-- hot inbound path. ASC priority so the first row wins.
CREATE INDEX channel_bindings_lookup_idx
    ON channel_bindings(tenant_id, endpoint_kind, endpoint_id, status, priority ASC);

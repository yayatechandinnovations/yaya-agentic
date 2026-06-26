-- W-A5 — step-up persistence. Three new tables and one tenant column. See
-- whatsapp-channel-auth-design.md §6.5–6.7.
--
-- The runtime sequence:
--   1. Authorizer denies a tool call with AuthzDecision.DenyOfferStepUp(hint)
--   2. StepUpCoordinator.handle mints a row in step_up_pending and asks the
--      provider (e.g. OtpEmailProvider) to deliver the OTP on the tenant's
--      configured secondary channel (NOT the WhatsApp sender we're elevating)
--   3. The bot tells the user to check email; the WhatsApp turn ends with no
--      LLM continuation
--   4. The user's next inbound is matched against step_up_pending; on success
--      channel_session_elevation gets the elevation row and the original
--      tool call retries against the elevated Principal
--
-- Tokens, codes, and link JTIs are NEVER written to audit_authz, turns, or
-- recorder payloads. They live only in step_up_pending (hashed) and the
-- secondary-channel delivery.

CREATE TABLE channel_session_elevation (
    session_id        VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    elevated_subject  VARCHAR(255) NOT NULL,
    added_scopes      TEXT[]       NOT NULL DEFAULT '{}'::text[],
    added_claims_json JSONB        NOT NULL DEFAULT '{}'::jsonb,
    audience          VARCHAR(255) NOT NULL,
    method            VARCHAR(64)  NOT NULL,
    minted_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    pending_audit_id  BIGINT
);

CREATE INDEX channel_session_elevation_tenant_idx
    ON channel_session_elevation(tenant_id);

CREATE INDEX channel_session_elevation_expiry_idx
    ON channel_session_elevation(expires_at);

CREATE TABLE step_up_pending (
    id                BIGSERIAL    PRIMARY KEY,
    session_id        VARCHAR(64)  NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    audience          VARCHAR(255) NOT NULL,
    method            VARCHAR(64)  NOT NULL,
    code_hash_b64     VARCHAR(255),                   -- OTP only; bcrypt-of-code
    deep_link_jti     VARCHAR(64),                    -- DeepLink only; arrives in W-A6
    target_address    VARCHAR(255) NOT NULL,          -- masked: ***@example.com / +1***1234
    attempts          INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 5,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    completion        VARCHAR(16),                    -- 'verified' | 'expired' | 'exhausted' | 'aborted'
    elevated_subject  VARCHAR(255),                   -- copied from provider's verify outcome
    added_scopes      TEXT[],                         -- copied from provider's verify outcome
    added_claims_json JSONB,                          -- copied from provider's verify outcome
    CONSTRAINT step_up_pending_completion_chk
        CHECK (completion IS NULL OR completion IN
               ('verified', 'expired', 'exhausted', 'aborted'))
);

-- §6.6: at most ONE open pending per (session, audience). Any concurrent
-- start attempt must wait for this to terminate.
CREATE UNIQUE INDEX step_up_pending_open_idx
    ON step_up_pending(session_id, audience)
    WHERE completion IS NULL;

CREATE INDEX step_up_pending_expiry_idx
    ON step_up_pending(expires_at) WHERE completion IS NULL;

CREATE TABLE step_up_audit (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sender_id        VARCHAR(64),
    session_id       VARCHAR(64)  NOT NULL,
    audience         VARCHAR(255) NOT NULL,
    method           VARCHAR(64)  NOT NULL,
    initial_subject  VARCHAR(255) NOT NULL,
    elevated_subject VARCHAR(255),
    outcome          VARCHAR(64)  NOT NULL,           -- 'started' | 'verified' | 'failed:<reason>' | 'expired' | 'aborted'
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,
    target_address   VARCHAR(255),                    -- masked, never raw
    correlation_ulid VARCHAR(26)  NOT NULL,
    pending_id       BIGINT       REFERENCES step_up_pending(id) ON DELETE SET NULL
);

CREATE INDEX step_up_audit_session_idx
    ON step_up_audit(tenant_id, session_id, started_at DESC);

CREATE INDEX step_up_audit_correlation_idx
    ON step_up_audit(correlation_ulid);

-- Tenant secondary comms — operator-configured "where do we ship OTP codes?"
-- Required before a StepUpAuthorizer can emit DenyOfferStepUp(OtpEmail) for
-- this tenant; the admin form (W-A9) refuses to attach a step-up policy
-- depending on a channel the tenant hasn't configured.
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS secondary_comms_json JSONB
        NOT NULL DEFAULT '{}'::jsonb;

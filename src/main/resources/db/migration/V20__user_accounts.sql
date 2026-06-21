-- M5a — user-accounts foundation. See
-- docs/specs/user-accounts/Backend-Data.md §2 and §4.1 for the full data
-- model. This migration is additive and reversible; the UUID cutover of
-- tenants.id (V22→V24) lands later.
--
-- Conventions:
--   * Token tables store sha256(rawToken). The raw token only ever appears
--     in the outbound mail body; a leaked DB snapshot cannot grant access.
--   * workspace_id is VARCHAR(64) here to match the V1-era tenants.id; V23
--     will sweep it to UUID along with every other child FK.
--   * Partial unique index on (token_hash) WHERE consumed_at IS NULL makes
--     "active token already exists" a DB-level violation, not an app check.

CREATE EXTENSION IF NOT EXISTS citext;

-- Users ----------------------------------------------------------------------

CREATE TABLE users (
    id                  UUID         PRIMARY KEY,
    email               CITEXT       NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    password_hash       TEXT         NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    is_platform_admin   BOOLEAN      NOT NULL DEFAULT false,
    email_verified_at   TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT users_status_chk
      CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DELETED'))
);

CREATE UNIQUE INDEX users_email_uniq         ON users (email);
CREATE INDEX        users_status_idx         ON users (status);
CREATE INDEX        users_platform_admin_idx ON users (is_platform_admin)
                                              WHERE is_platform_admin = true;
CREATE INDEX        users_deleted_at_idx     ON users (deleted_at)
                                              WHERE deleted_at IS NOT NULL;

-- Memberships ----------------------------------------------------------------

CREATE TABLE user_workspace_memberships (
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    workspace_id  VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    role          VARCHAR(32)  NOT NULL,
    added_by      UUID         REFERENCES users(id) ON DELETE SET NULL,
    added_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, workspace_id),
    CONSTRAINT user_workspace_memberships_role_chk
      CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER'))
);

CREATE INDEX user_workspace_memberships_workspace_idx
    ON user_workspace_memberships (workspace_id);
CREATE INDEX user_workspace_memberships_role_idx
    ON user_workspace_memberships (role);

-- Invitations ----------------------------------------------------------------
-- OWNER is intentionally absent from the CHECK: owners are minted only via
-- signup (default workspace bootstrap) or by explicit promotion from an
-- existing OWNER. There is no "invite as OWNER" path.

CREATE TABLE user_invitations (
    id            UUID         PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email         CITEXT       NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    token_hash    BYTEA        NOT NULL,
    invited_by    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at    TIMESTAMPTZ  NOT NULL,
    consumed_at   TIMESTAMPTZ,
    consumed_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT user_invitations_role_chk
      CHECK (role IN ('ADMIN','MEMBER','VIEWER'))
);

CREATE UNIQUE INDEX user_invitations_token_hash_pending_uniq
    ON user_invitations (token_hash) WHERE consumed_at IS NULL;
CREATE INDEX user_invitations_email_pending_idx
    ON user_invitations (email)      WHERE consumed_at IS NULL;
CREATE INDEX user_invitations_workspace_idx
    ON user_invitations (workspace_id);
CREATE INDEX user_invitations_expires_idx
    ON user_invitations (expires_at) WHERE consumed_at IS NULL;

-- Email verification + password reset tokens --------------------------------

CREATE TABLE user_email_verification_tokens (
    token_hash    BYTEA        PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at    TIMESTAMPTZ  NOT NULL,
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX user_email_verification_tokens_user_pending_idx
    ON user_email_verification_tokens (user_id) WHERE consumed_at IS NULL;
CREATE INDEX user_email_verification_tokens_expires_idx
    ON user_email_verification_tokens (expires_at) WHERE consumed_at IS NULL;

CREATE TABLE user_password_reset_tokens (
    token_hash    BYTEA        PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at    TIMESTAMPTZ  NOT NULL,
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX user_password_reset_tokens_user_pending_idx
    ON user_password_reset_tokens (user_id) WHERE consumed_at IS NULL;
CREATE INDEX user_password_reset_tokens_expires_idx
    ON user_password_reset_tokens (expires_at) WHERE consumed_at IS NULL;

-- Audit ----------------------------------------------------------------------
-- detail_json never carries raw PII — only ids, hashes, and role names.
-- See docs/specs/user-accounts/Backend-Data.md §2.5 for the canonical
-- `kind` taxonomy.

CREATE TABLE audit_user_events (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       UUID         REFERENCES users(id) ON DELETE SET NULL,
    workspace_id  VARCHAR(64)  REFERENCES tenants(id) ON DELETE SET NULL,
    kind          VARCHAR(64)  NOT NULL,
    detail_json   JSONB,
    -- client_ip is plain text rather than INET so the JPA entity can pass
    -- it as a String without a Postgres-specific cast layer. The existing
    -- audit_operator_login / audit_authz tables follow the same convention.
    client_ip     TEXT,
    user_agent    TEXT,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX audit_user_events_kind_time_idx
    ON audit_user_events (kind, occurred_at DESC);
CREATE INDEX audit_user_events_user_time_idx
    ON audit_user_events (user_id, occurred_at DESC);
CREATE INDEX audit_user_events_workspace_time_idx
    ON audit_user_events (workspace_id, occurred_at DESC);
CREATE INDEX audit_user_events_detail_gin
    ON audit_user_events USING GIN (detail_json);

-- Mail outbox ----------------------------------------------------------------
-- idempotency_key is sha256(template + token_hash) for token-bearing mails so
-- a re-enqueue can't double-send. The drainer (M5a phase 2) picks rows where
-- status IN ('PENDING','RETRYING') AND next_attempt_at <= now().

CREATE TABLE email_outbox (
    id               BIGSERIAL    PRIMARY KEY,
    to_address       TEXT         NOT NULL,
    template_id      VARCHAR(64)  NOT NULL,
    subject          TEXT         NOT NULL,
    body_html        TEXT,
    body_text        TEXT         NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       TEXT,
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT email_outbox_status_chk
      CHECK (status IN ('PENDING','RETRYING','SENT','DEAD'))
);

CREATE UNIQUE INDEX email_outbox_idempotency_uniq
    ON email_outbox (idempotency_key);
CREATE INDEX email_outbox_pending_idx
    ON email_outbox (next_attempt_at)
    WHERE status IN ('PENDING','RETRYING');
CREATE INDEX email_outbox_dead_idx
    ON email_outbox (status) WHERE status = 'DEAD';

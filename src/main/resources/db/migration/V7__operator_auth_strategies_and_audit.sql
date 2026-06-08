-- Phase 3 of operator-auth: persisted strategy config + login audit.
-- See docs/design/operator-auth-design.md §5.7, §10, §11.
--
-- operator_auth_config is a singleton (id=1 enforced). The four JSONB
-- columns mirror the design's four configurable layers (request shape,
-- success criteria, identity mapping, failure mapping). The delegate
-- secret is stored encrypted (BYTEA) via Spring's Encryptors.text — see
-- SecretCipher.

CREATE TABLE operator_auth_config (
    id                              INTEGER     PRIMARY KEY DEFAULT 1 CHECK (id = 1),

    bootstrap_enabled               BOOLEAN     NOT NULL DEFAULT TRUE,
    bootstrap_username              TEXT        NOT NULL,
    bootstrap_password_hash         TEXT        NOT NULL,

    http_delegate_enabled           BOOLEAN     NOT NULL DEFAULT FALSE,
    http_delegate_url               TEXT,
    http_delegate_secret_enc        BYTEA,
    http_delegate_timeout_ms        INTEGER     NOT NULL DEFAULT 5000,
    http_delegate_require_https     BOOLEAN     NOT NULL DEFAULT TRUE,

    -- The four §5 layers, each stored as JSONB so the shape can evolve
    -- without a schema change. Defaults applied by the service when null.
    http_delegate_request_json      JSONB,
    http_delegate_success_json      JSONB,
    http_delegate_identity_json     JSONB,
    http_delegate_failure_json      JSONB,

    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                      TEXT
);

CREATE TABLE audit_operator_login (
    id              TEXT        PRIMARY KEY,         -- ULID
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    username        TEXT        NOT NULL,            -- never the password
    decision        TEXT        NOT NULL CHECK (decision IN ('ALLOW','DENY')),
    source          TEXT,                            -- BOOTSTRAP | HTTP_DELEGATE | null
    audit_reason    TEXT,                            -- distinct from user-facing message
    client_ip       TEXT,
    user_agent      TEXT,
    attempt_id      TEXT        NOT NULL,
    duration_ms     INTEGER
);

CREATE INDEX idx_audit_operator_login_username_at ON audit_operator_login(username, at DESC);
CREATE INDEX idx_audit_operator_login_decision_at ON audit_operator_login(decision, at DESC);

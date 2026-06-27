-- API keys — workspace-scoped, role-bound, revocable bearer credentials for
-- the operator plane. See docs/design/api-keys-design.md §7.
--
-- Schema notes (deviations from the design doc, which assumed the
-- V22→V24 tenants.id UUID cutover had already landed — it has not):
--   * workspace_id is VARCHAR(64) REFERENCES tenants(id), matching
--     user_workspace_memberships (V20) and every other child FK today.
--   * audit client_ip is TEXT (existing audit-table convention, see V20
--     audit_user_events) rather than INET, so the JPA entity passes a
--     String without a Postgres-specific cast layer. client_ip_hash stays
--     BYTEA because it is a fixed-width sha256 digest.

-- api_keys --------------------------------------------------------------------
-- secret_hash is sha256(secret); the raw secret is returned exactly once on
-- create and never stored. key_prefix is the first 11 chars (non-secret) used
-- for indexed lookup + UI display.

CREATE TABLE api_keys (
    id                    UUID PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                  VARCHAR(64) NOT NULL,
    key_prefix            VARCHAR(16) NOT NULL,                     -- "yk_live_abc" — non-secret, indexed
    secret_hash           BYTEA NOT NULL,                           -- sha256(secret); UNIQUE; never logged
    role                  VARCHAR(32) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
    created_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ,                              -- NULL = never
    last_used_at          TIMESTAMPTZ,
    last_used_ip          TEXT,
    revoked_at            TIMESTAMPTZ,
    revoked_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX ix_api_keys_secret_hash ON api_keys(secret_hash);
CREATE INDEX        ix_api_keys_prefix      ON api_keys(key_prefix);
CREATE INDEX        ix_api_keys_by_ws       ON api_keys(workspace_id, revoked_at NULLS FIRST);

-- Hot-path lookup: by prefix WHERE active. Partial index keeps it small.
CREATE INDEX ix_api_keys_active_lookup
    ON api_keys(key_prefix)
    WHERE revoked_at IS NULL;

-- audit_api_key_events --------------------------------------------------------
-- Key-centric event log (CREATE / REVOKE / RENAME / EXPIRE / USE_DENIED).
-- api_key_id is intentionally NOT a FK so audit rows survive key deletion.
-- The IP split (§12 / §14 D3) is enforced by a CHECK: USE_DENIED stores the
-- full client_ip; state-change events store a salted client_ip_hash only.

CREATE TABLE audit_api_key_events (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    VARCHAR(64) REFERENCES tenants(id) ON DELETE CASCADE,
    api_key_id      UUID,                                           -- not FK; survives key deletion
    actor_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    kind            VARCHAR(64) NOT NULL,                           -- CREATE | REVOKE | RENAME | EXPIRE | USE_DENIED
    detail_json     JSONB,
    client_ip       TEXT,                                           -- full IP — populated only for USE_DENIED
    client_ip_hash  BYTEA,                                          -- sha256(ip || salt) — for CREATE/REVOKE/RENAME
    user_agent      TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_api_key_events_ip_split_chk CHECK (
        (kind = 'USE_DENIED' AND client_ip IS NOT NULL  AND client_ip_hash IS NULL)
     OR (kind <> 'USE_DENIED' AND client_ip IS NULL     AND client_ip_hash IS NOT NULL)
     OR (client_ip IS NULL    AND client_ip_hash IS NULL)          -- both NULL when source IP is unknown
    )
);
CREATE INDEX ix_audit_api_key_events_ws_time  ON audit_api_key_events(workspace_id, occurred_at DESC);
CREATE INDEX ix_audit_api_key_events_key_time ON audit_api_key_events(api_key_id, occurred_at DESC);

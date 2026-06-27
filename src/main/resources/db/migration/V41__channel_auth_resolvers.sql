-- PR 1 — channel_auth_resolvers + per-binding linkage + per-session JWT
-- storage + audit trail. See docs/design/channel-auth-resolver-design.md
-- §5 (SPI), §7 (schema), §9 (lifecycle), §12 (audit).
--
-- v1 model:
--   1. Tenants register an AuthResolver (BEAN or HTTP) per (tenant_id, name).
--   2. A ChannelBinding references one resolver by id, plus an on_unresolved
--      policy (REJECT | ANONYMOUS) for when the resolver returns 404.
--   3. The minted JWT lives encrypted on the session row, replaced on
--      refresh, wiped on session end. A Redis cache (separate, not in DB)
--      survives across sessions until the JWT's own exp retires it.
--   4. Every resolver call is audited to auth_resolver_calls — the source
--      of truth for "did the tenant's webhook respond on time, and how."
--
-- The service_secret used by HTTP resolvers to authenticate yaya->tenant
-- (SIGNED_JWT / BEARER) is stored AES-256-GCM-encrypted via JwtSecretCipher
-- as a (ciphertext, iv) text pair — same pattern as tenant_jwt_secrets.

CREATE TABLE channel_auth_resolvers (
    id                       BIGSERIAL    PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL
                                          REFERENCES tenants(id)
                                          ON DELETE CASCADE,
    name                     VARCHAR(64)  NOT NULL,
    description              VARCHAR(255),
    kind                     VARCHAR(16)  NOT NULL,                 -- 'BEAN' | 'HTTP'

    -- BEAN only
    bean_name                VARCHAR(128),

    -- HTTP only
    http_path                VARCHAR(256),
    service_auth_kind        VARCHAR(32),                           -- 'NONE' | 'BEARER' | 'SIGNED_JWT'
    service_secret_ciphertext TEXT,                                 -- AES-256-GCM via JwtSecretCipher
    service_secret_iv         VARCHAR(64),                          -- 12-byte IV, base64
    timeout_ms               INT          NOT NULL DEFAULT 3000,

    -- Caching & refresh policy
    cache_enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    max_cache_seconds        INT,                                   -- null = bounded only by JWT exp
    refresh_window_seconds   INT          NOT NULL DEFAULT 60,
    max_token_age_seconds    INT          NOT NULL DEFAULT 3600,

    on_refresh_failure       VARCHAR(32)  NOT NULL DEFAULT 'REJECT',  -- 'REJECT' | 'DOWNGRADE_TO_ANONYMOUS'

    status                   VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- 'ACTIVE' | 'DEACTIVATED'

    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT channel_auth_resolvers_name_unique
        UNIQUE (tenant_id, name),

    CONSTRAINT channel_auth_resolvers_kind_chk
        CHECK (kind IN ('BEAN','HTTP')),
    CONSTRAINT channel_auth_resolvers_kind_shape_chk
        CHECK ((kind = 'BEAN' AND bean_name IS NOT NULL AND http_path IS NULL)
            OR (kind = 'HTTP' AND http_path IS NOT NULL AND bean_name IS NULL)),
    CONSTRAINT channel_auth_resolvers_service_auth_chk
        CHECK (service_auth_kind IS NULL
            OR service_auth_kind IN ('NONE','BEARER','SIGNED_JWT')),
    CONSTRAINT channel_auth_resolvers_secret_pair_chk
        CHECK ((service_secret_ciphertext IS NULL AND service_secret_iv IS NULL)
            OR (service_secret_ciphertext IS NOT NULL AND service_secret_iv IS NOT NULL)),
    CONSTRAINT channel_auth_resolvers_on_refresh_chk
        CHECK (on_refresh_failure IN ('REJECT','DOWNGRADE_TO_ANONYMOUS')),
    CONSTRAINT channel_auth_resolvers_status_chk
        CHECK (status IN ('ACTIVE','DEACTIVATED')),
    CONSTRAINT channel_auth_resolvers_timeout_chk
        CHECK (timeout_ms BETWEEN 100 AND 30000),
    CONSTRAINT channel_auth_resolvers_refresh_window_chk
        CHECK (refresh_window_seconds BETWEEN 0 AND 600),
    CONSTRAINT channel_auth_resolvers_max_age_chk
        CHECK (max_token_age_seconds BETWEEN 60 AND 86400),
    CONSTRAINT channel_auth_resolvers_max_cache_chk
        CHECK (max_cache_seconds IS NULL OR max_cache_seconds BETWEEN 1 AND 86400)
);

CREATE INDEX channel_auth_resolvers_tenant_status_idx
    ON channel_auth_resolvers(tenant_id, status);

-- channel_bindings: link to a resolver, declare what to do when it 404s.
-- The W-A1 series stored its identity policy inside session_policy_json,
-- so there are no policy COLUMNS to drop — only the JSON shape changes,
-- and that drift is handled in PR 2 / task #121 (already shipped).
ALTER TABLE channel_bindings
    ADD COLUMN auth_resolver_id BIGINT
                                  REFERENCES channel_auth_resolvers(id)
                                  ON DELETE SET NULL;

ALTER TABLE channel_bindings
    ADD COLUMN on_unresolved VARCHAR(16) NOT NULL DEFAULT 'REJECT';

ALTER TABLE channel_bindings
    ADD CONSTRAINT channel_bindings_on_unresolved_chk
        CHECK (on_unresolved IN ('REJECT','ANONYMOUS'));

CREATE INDEX channel_bindings_auth_resolver_idx
    ON channel_bindings(auth_resolver_id)
    WHERE auth_resolver_id IS NOT NULL;

-- sessions: per-session JWT storage, AES-256-GCM (two text columns,
-- matching tenant_jwt_secrets). NULL = ANONYMOUS path or no resolver bound.
ALTER TABLE sessions
    ADD COLUMN auth_token_ciphertext TEXT;

ALTER TABLE sessions
    ADD COLUMN auth_token_iv VARCHAR(64);

ALTER TABLE sessions
    ADD CONSTRAINT sessions_auth_token_pair_chk
        CHECK ((auth_token_ciphertext IS NULL AND auth_token_iv IS NULL)
            OR (auth_token_ciphertext IS NOT NULL AND auth_token_iv IS NOT NULL));

-- auth_resolver_calls: the audit row written for every resolver call,
-- whether cache-hit (no actual call) or cache-miss (real round-trip).
-- 90-day retention enforced by a cron job (separate PR — out of scope here).
-- The caller_sha256 column NEVER stores raw E.164 — it's the SHA-256 of
-- the lower-hex callerPhone, same hash as the Redis cache key namespace,
-- so an operator querying by phone can match cache and audit consistently.
CREATE TABLE auth_resolver_calls (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resolver_name       VARCHAR(64)  NOT NULL,
    session_id          UUID,
    sender_id           VARCHAR(64)  NOT NULL,
    caller_sha256       VARCHAR(64)  NOT NULL,                    -- 64 lowercase hex chars
    channel             VARCHAR(32)  NOT NULL,
    reason              VARCHAR(16)  NOT NULL,                    -- 'INITIAL' | 'REFRESH'
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    elapsed_ms          INT,
    status              VARCHAR(32)  NOT NULL,                    -- 'ok' | 'not_found' | 'timeout' | 'bad_token' | 'self_auth_failed' | 'transport_error' | 'redis_unavailable' | 'allowlist_denied'
    cache_state         VARCHAR(16)  NOT NULL DEFAULT 'MISS',     -- 'HIT' | 'MISS' | 'REFRESH'
    http_status         INT,                                       -- nullable; only HTTP transport sets this
    error_summary       VARCHAR(512),                              -- redacted, no token bytes
    CONSTRAINT auth_resolver_calls_reason_chk
        CHECK (reason IN ('INITIAL','REFRESH')),
    CONSTRAINT auth_resolver_calls_cache_state_chk
        CHECK (cache_state IN ('HIT','MISS','REFRESH'))
);

CREATE INDEX auth_resolver_calls_tenant_started_idx
    ON auth_resolver_calls(tenant_id, started_at DESC);

CREATE INDEX auth_resolver_calls_caller_idx
    ON auth_resolver_calls(tenant_id, caller_sha256, started_at DESC);

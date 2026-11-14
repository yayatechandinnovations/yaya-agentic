-- Sessions opened from a tenant's own app with the signed-in user's token, per-profile
-- model / tool steps, and usage metering with a monthly allowance
-- (docs/guides/admin-panel-sessions.md).

-- Per-profile model and tool steps. NULL = the deployment's model / 5 rounds, so every
-- existing profile keeps its behaviour. `metered` profiles count against the tenant's
-- monthly allowance; everything else (customer-facing chat) is never blocked by it.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS model VARCHAR(128);

ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS max_tool_rounds INT;

ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS metered BOOLEAN NOT NULL DEFAULT FALSE;

-- What a trusted HS256 issuer proves about its tokens for this tenant:
--   required_claims_json  {"claim": "value"} every token must carry, which pins an
--                         issuer shared by several tenants to this one;
--   principal_kind        the label a profile's metadata.principalKinds is matched against.
ALTER TABLE tenant_jwt_secrets
    ADD COLUMN IF NOT EXISTS principal_kind VARCHAR(128);

ALTER TABLE tenant_jwt_secrets
    ADD COLUMN IF NOT EXISTS required_claims_json TEXT;

-- Monthly allowance per tenant. No row, or NULL, = unlimited.
CREATE TABLE IF NOT EXISTS tenant_usage_quotas (
    tenant_id         VARCHAR(64)  PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    monthly_messages  INT,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_usage_quotas_nonneg_chk CHECK (monthly_messages IS NULL OR monthly_messages >= 0)
);

-- The allowance counter: turns of METERED profiles only, one row per tenant and month,
-- so admission is one conditional upsert on one row.
CREATE TABLE IF NOT EXISTS tenant_usage_monthly (
    tenant_id      VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    month          CHAR(7)     NOT NULL,
    messages       BIGINT      NOT NULL DEFAULT 0,
    input_tokens   BIGINT      NOT NULL DEFAULT 0,
    output_tokens  BIGINT      NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, month),
    CONSTRAINT tenant_usage_monthly_nonneg_chk CHECK (messages >= 0)
);

-- Every profile, metered or not, for cost reporting.
CREATE TABLE IF NOT EXISTS profile_usage_monthly (
    tenant_id      VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    profile_id     VARCHAR(128) NOT NULL,
    month          CHAR(7)      NOT NULL,
    metered        BOOLEAN      NOT NULL DEFAULT FALSE,
    messages       BIGINT       NOT NULL DEFAULT 0,
    input_tokens   BIGINT       NOT NULL DEFAULT 0,
    output_tokens  BIGINT       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, profile_id, month)
);

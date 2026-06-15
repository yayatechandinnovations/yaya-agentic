-- Per-tenant HMAC-signed JWT trust config consumed by
-- SharedSecretJwtAuthenticator. Lets a tenant accept HS256-signed JWTs
-- minted by an internal identity service that doesn't speak OIDC
-- (no JWKS, no asymmetric keys) — same operator workflow as
-- tenant_oidc_issuers, different alg.
--
-- Trust model: yaya-agentic holds the HMAC secret. Whoever holds the
-- secret can both sign and verify, which is equivalent to the service-
-- token authenticator's posture — the upside is we still extract a
-- per-user principal from the verified JWT payload, instead of stamping
-- every call with a tenant-level identity.
--
-- The secret is stored AES-256-GCM-encrypted at rest with a key from
-- yaya.agentic.secrets.encryption-key (env: YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY).
-- A DB dump on its own doesn't leak signing keys; the operator must also
-- exfiltrate the env-supplied master key.
--
-- Lookup at session start (see SharedSecretJwtAuthenticator):
--   1. JWT alg header must start with "HS" (HS256/HS384/HS512). Otherwise
--      yield and let the chain continue.
--   2. Read tenant_id from AuthContext, match JWT iss against this table.
--   3. Decrypt the secret, MAC-verify the signature, assert exp/nbf,
--      then check `aud` against `audiences` (empty list = no aud check).
--   4. No row matches -> AuthenticationException "untrusted issuer".

CREATE TABLE tenant_jwt_secrets (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)   NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    iss               VARCHAR(512)  NOT NULL,
    secret_ciphertext TEXT          NOT NULL,
    secret_iv         VARCHAR(64)   NOT NULL,
    audiences         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_jwt_secrets_unique UNIQUE (tenant_id, iss)
);

CREATE INDEX tenant_jwt_secrets_tenant_idx
    ON tenant_jwt_secrets (tenant_id);

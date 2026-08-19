-- One-click YayaOps provisioning (smart-lottery yayaops-one-click design, gap A):
-- DB-backed per-tenant service-token HMAC secrets, replacing the static
-- yaya.agentic.auth.service-token.tenant-secrets config map as the primary
-- source. ServiceTokenAuthenticator resolves DB-first with the config map as
-- fallback, so existing env-configured deployments keep working unchanged.
-- Secret is AES-256-GCM encrypted at rest (JwtSecretCipher), same shape as
-- tenant_jwt_secrets — HMAC verification needs the cleartext back, so a hash
-- is not an option.
CREATE TABLE tenant_service_token_secrets (
    tenant_id            VARCHAR(64) PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    secret_ciphertext    TEXT NOT NULL,
    secret_iv            VARCHAR(64) NOT NULL,
    rotated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

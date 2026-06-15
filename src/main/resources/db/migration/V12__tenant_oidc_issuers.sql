-- Per-tenant trusted JWT issuer config consumed by OidcAuthenticator.
-- First-class alternative to the global yaya.agentic.auth.oidc.issuers
-- YAML list: each tenant declares its own auth boundary at runtime via
-- POST /v1/admin/tenants/{id}/oidc-issuers — no backend restart and no
-- shared config file. YAML stays supported as a fallback so ops who
-- prefer file-based config can still use it.
--
-- Lookup at session start (see OidcAuthenticator):
--   1. Read tenant_id from the inbound AuthContext.
--   2. Match the JWT's `iss` claim against this table's rows for that tenant.
--   3. If hit: validate signature against jwks_uri, then check `aud` against
--      `audiences` (empty list = no aud check).
--   4. If miss: fall back to the global YAML issuers list.
--   5. If still no match: AuthenticationException "untrusted issuer".
--
-- Uniqueness is (tenant_id, iss): the same issuer can be registered for
-- multiple tenants (shared IdP), but only once per tenant.

CREATE TABLE tenant_oidc_issuers (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)   NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    iss         VARCHAR(512)  NOT NULL,
    jwks_uri    VARCHAR(1024) NOT NULL,
    audiences   JSONB         NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_oidc_issuers_unique UNIQUE (tenant_id, iss)
);

CREATE INDEX tenant_oidc_issuers_tenant_idx
    ON tenant_oidc_issuers (tenant_id);

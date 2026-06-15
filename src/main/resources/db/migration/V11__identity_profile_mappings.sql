-- Per-tenant claim → profile mapping consumed by IdentityProfileResolver.
-- Lets HTTP-only consumers point yaya-agentic at a profile based on a JWT
-- claim without forking the codebase. Mapping is data, not code.
--
-- Matching: at session start the resolver loads the tenant's rows ordered
-- by priority ASC, then for each row reads principal.claims[claim_name]
-- and asks "does this row match?":
--   - if the claim value is a String  → equality with claim_value
--   - if the claim value is a List    → claim_value is in the list
--   - if the claim value is a space-separated String (OIDC scope shape)
--                                     → claim_value is one of the tokens
-- First match wins. No match → resolver returns Optional.empty() and
-- the chain falls through to the next resolver (channel, fallback).
--
-- Uniqueness is (tenant_id, claim_name, claim_value): the same claim value
-- can't map to two different profiles within the same tenant.

CREATE TABLE identity_profile_mappings (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    claim_name      VARCHAR(128) NOT NULL,
    claim_value     VARCHAR(255) NOT NULL,
    profile_id      VARCHAR(128) NOT NULL,
    profile_version INT          NOT NULL DEFAULT 1,
    priority        INT          NOT NULL DEFAULT 100,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT identity_profile_mappings_unique
        UNIQUE (tenant_id, claim_name, claim_value)
);

CREATE INDEX identity_profile_mappings_tenant_priority_idx
    ON identity_profile_mappings (tenant_id, priority, id);

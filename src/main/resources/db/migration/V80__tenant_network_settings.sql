-- Runtime-mutable per-tenant settings for the Corillo network entry point
-- (player_network_v1). Lifts the yaya.agentic.network.* config flags into DB so
-- an operator can enable the feature and flip play/claim from the admin console
-- with no restart. Read live by the engine (cached, evicted on change); the YAML
-- @ConfigurationProperties remain as fallbacks when no row exists.
CREATE TABLE tenant_network_settings (
    tenant_id          VARCHAR(64) PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    enabled            BOOLEAN     NOT NULL DEFAULT FALSE,
    play_enabled       BOOLEAN     NOT NULL DEFAULT FALSE,
    claim_enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    hub_base_url       VARCHAR(1024),
    -- A REFERENCE (env var name) to the X-Agent-Auth secret, never the secret.
    hub_credential_ref VARCHAR(256),
    -- The auto-provisioned chat entry point id, once enabled.
    entry_point_id     VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

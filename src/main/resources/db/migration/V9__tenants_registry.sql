-- Tenant becomes a first-class configuration aggregate
-- per docs/design/tenant-registry-design.md §3.2.
--
-- Adds: status + lifecycle columns, host_base_url + allowlists,
-- inbound_origin_allowlist, require_https, default authenticator binding
-- + default recording strategy, updated_at / created_by / archived_at.
--
-- Existing rows backfill to status=ACTIVE with host_base_url=NULL.
-- An ACTIVE tenant without a host_base_url is reported as misconfigured
-- by /v1/admin/tenants/{id}/health — we intentionally do not fabricate a
-- localhost default, because silent defaults hide misconfiguration as
-- failed dispatches (§3.2).

ALTER TABLE tenants RENAME COLUMN name TO display_name;

ALTER TABLE tenants
    ADD COLUMN status                              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN host_base_url                       TEXT,
    ADD COLUMN host_base_url_allowlist             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN inbound_origin_allowlist            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN require_https                       BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN default_authenticator_binding_id    VARCHAR(128),
    ADD COLUMN default_recording_strategy_id       BIGINT,
    ADD COLUMN updated_at                          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ADD COLUMN created_by                          VARCHAR(255),
    ADD COLUMN archived_at                         TIMESTAMPTZ;

ALTER TABLE tenants
    ADD CONSTRAINT tenants_status_chk
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED'));

-- Composite FK: (tenant_id, default_authenticator_binding_id) must reference
-- a binding *owned by this tenant*. Nullable on the tenant side so registration
-- can precede the first auth binding (chicken-and-egg). The binding linkage
-- is required for ACTIVE tenants at validation time, not at the DB level.
ALTER TABLE tenants
    ADD CONSTRAINT tenants_default_auth_fk
        FOREIGN KEY (id, default_authenticator_binding_id)
            REFERENCES auth_bindings(tenant_id, id)
            DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE tenants
    ADD CONSTRAINT tenants_default_recording_fk
        FOREIGN KEY (default_recording_strategy_id)
            REFERENCES recording_strategies(id)
            DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX tenants_status_idx ON tenants (status);

-- Audit row when an inbound request is denied by the origin allowlist (§5).
CREATE TABLE audit_inbound_origin_denied (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    VARCHAR(64),                   -- nullable: unknown tenant case
    principal    VARCHAR(255),
    origin       TEXT,
    path         TEXT,
    expected     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX audit_inbound_origin_denied_tenant_idx
    ON audit_inbound_origin_denied (tenant_id, created_at DESC);

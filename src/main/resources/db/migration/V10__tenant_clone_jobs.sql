-- Audit table for cross-tenant profile clones per
-- docs/design/tenant-registry-design.md §7.7.
--
-- Every clone (dry-run or applied) writes a row. plan_json captures the
-- resolved plan; status discriminates DRY_RUN / APPLIED / FAILED.
-- Dry-runs are kept for 30 days (cleanup is a separate job, M3 work);
-- applied rows live for the life of the tenant for forensics.

CREATE TABLE tenant_clone_jobs (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_tenant        VARCHAR(64)  NOT NULL,
    destination_tenant   VARCHAR(64)  NOT NULL,
    source_profile_id    VARCHAR(128) NOT NULL,
    source_version       INTEGER      NOT NULL,
    destination_profile_id VARCHAR(128),
    status               VARCHAR(16)  NOT NULL,
    plan_json            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    error_json           JSONB,
    applied_at           TIMESTAMPTZ,
    applied_by           VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CHECK (status IN ('DRY_RUN', 'APPLIED', 'FAILED'))
);

CREATE INDEX tenant_clone_jobs_dest_idx ON tenant_clone_jobs (destination_tenant, created_at DESC);
CREATE INDEX tenant_clone_jobs_src_idx  ON tenant_clone_jobs (source_tenant,      created_at DESC);
CREATE INDEX tenant_clone_jobs_status_idx ON tenant_clone_jobs (status, created_at);

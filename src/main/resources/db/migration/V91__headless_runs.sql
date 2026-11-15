-- Headless runs (docs/guides/admin-panel-sessions.md §17): a tenant service
-- submits a stored question, the profile's agent answers it with nobody
-- present, and the service polls for the answer.
--
-- One row per run. The principal token a run forwards to the tenant's backend
-- is never written here (it lives in the queued task only), so a run that the
-- process did not finish cannot be picked up again: it is marked
-- failed / interrupted at the next start. The answer is erased 24 hours after
-- the run was created and the row 30 days after.
CREATE TABLE IF NOT EXISTS headless_runs (
    id               VARCHAR(40)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    profile_id       VARCHAR(128) NOT NULL,
    profile_version  INT          NOT NULL,
    idem_key         VARCHAR(160) NOT NULL,
    principal_sub    VARCHAR(255) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    question         TEXT         NOT NULL,
    answer           TEXT,
    failure_code     VARCHAR(40),
    failure_message  VARCHAR(500),
    usage_json       TEXT,
    tools_json       TEXT,
    context_json     TEXT,
    -- The conversation the run was recorded as, for the operator's inspector.
    session_id       UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    CONSTRAINT headless_runs_status_chk
        CHECK (status IN ('queued', 'running', 'completed', 'failed')),
    CONSTRAINT headless_runs_idem_uq UNIQUE (tenant_id, idem_key)
);

-- Runs in flight (the per-tenant ceiling, the start-up sweep) and housekeeping by age.
CREATE INDEX IF NOT EXISTS ix_headless_runs_status_created
    ON headless_runs (status, created_at);

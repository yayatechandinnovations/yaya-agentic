-- Durable, restart-safe queue for knowledge ingestion
-- (knowledge-file-upload-design.md §9). Uploading + attaching files enqueues a
-- job here; a worker pool claims jobs with FOR UPDATE SKIP LOCKED (safe across
-- replicas, no coordinator), runs the loader→chunk→embed pipeline off the event
-- loop, and retries with exponential backoff. Replaces synchronous reindex as
-- the production ingestion path for uploaded sources.

CREATE TABLE knowledge_ingestion_jobs (
    id             UUID         PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    source_id      VARCHAR(128) NOT NULL,
    source_version INT          NOT NULL,
    state          VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',   -- QUEUED | RUNNING | DONE | FAILED
    attempts       INT          NOT NULL DEFAULT 0,
    max_attempts   INT          NOT NULL DEFAULT 5,
    last_error     TEXT,
    run_after      TIMESTAMPTZ  NOT NULL DEFAULT now(),       -- backoff gate
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The claim query filters state='QUEUED' AND run_after<=now() ordered by created_at.
CREATE INDEX ix_knowledge_jobs_claim
    ON knowledge_ingestion_jobs (state, run_after, created_at);

-- Status endpoint reads the latest job for a (tenant, source).
CREATE INDEX ix_knowledge_jobs_source
    ON knowledge_ingestion_jobs (tenant_id, source_id, created_at DESC);

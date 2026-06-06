-- Recorder strategy bindings + transactional outbox + recorder-ops audit.
-- Design §5.9. M1 wires the outbox with zero attached sinks; M5 adds them.

CREATE TABLE recording_strategies (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    scope_kind    VARCHAR(16) NOT NULL,   -- 'TENANT' | 'PROFILE'
    scope_id      VARCHAR(128) NOT NULL,
    strategy_json JSONB NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, scope_kind, scope_id, version),
    CHECK (scope_kind IN ('TENANT','PROFILE'))
);

CREATE TABLE recorder_outbox (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    session_id        UUID        NOT NULL,
    turn_id           UUID,
    sink_id           VARCHAR(128) NOT NULL,
    payload_json      JSONB NOT NULL,
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at     TIMESTAMPTZ,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX recorder_outbox_pending_idx
    ON recorder_outbox (status, next_attempt_at)
    WHERE status = 'PENDING';

CREATE TABLE audit_recorder_ops (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    op                 VARCHAR(16) NOT NULL,   -- READ | REDACT | DELETE | EXPORT | ARCHIVE
    session_id         UUID,
    principal_subject  VARCHAR(255),
    actor_subject      VARCHAR(255),
    request_json       JSONB NOT NULL,
    result_json        JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (op IN ('READ','REDACT','DELETE','EXPORT','ARCHIVE'))
);
CREATE INDEX audit_recorder_ops_tenant_time_idx
    ON audit_recorder_ops (tenant_id, created_at DESC);

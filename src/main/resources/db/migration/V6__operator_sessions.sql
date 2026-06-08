-- Phase 1 of operator-auth: server-side sessions for the admin console.
-- See docs/design/operator-auth-design.md §11.
--
-- id_hash holds the hex-encoded SHA-256 of the random session token issued
-- to the browser. The raw token never lands in the DB — a leaked snapshot
-- can't grant session takeover.

CREATE TABLE operator_sessions (
    id_hash          TEXT        PRIMARY KEY,
    operator_subject TEXT        NOT NULL,
    operator_display TEXT        NOT NULL,
    source           TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_ip        TEXT,
    user_agent       TEXT,
    attributes       JSONB
);

CREATE INDEX idx_operator_sessions_expires ON operator_sessions(expires_at);

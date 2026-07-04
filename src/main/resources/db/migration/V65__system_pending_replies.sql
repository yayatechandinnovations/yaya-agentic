-- SM-2 reply loop (system-messaging-design.md §8). A durable pending-reply row
-- per LLM-mediated system message, so the correlation (WHO to answer, opaque
-- routing handle) survives the operator being offline for days. On resume it is
-- re-captured into entity memory and pinned onto the response tool call — the
-- engine decides *to whom*, never the LLM.
CREATE TABLE system_pending_replies (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    sender_id       VARCHAR(64)  NOT NULL,
    originator_name VARCHAR(200),
    originator_ref  VARCHAR(255) NOT NULL,
    subject         VARCHAR(200),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT system_pending_replies_status_chk
        CHECK (status IN ('PENDING','RESOLVED'))
);
-- Hot path: pending replies for a session, newest first (most-recent default, §8.3).
CREATE INDEX system_pending_replies_session_idx
    ON system_pending_replies(session_id, status, created_at DESC);

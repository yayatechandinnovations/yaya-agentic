-- W4 — channel_sessions (the WhatsApp-side index of "is there a live engine
-- session for this end user?") and inbound_messages (the dedup/audit log
-- for every webhook we 200'd). Both per whatsapp-channel-design.md §13.
--
-- channel_sessions is intentionally NOT the recorder's sessions table — it
-- lives outside the recorder so swapping recorder backends doesn't lose
-- channel-side continuity, and the recorder never sees phone numbers (§9.2
-- last paragraph).

CREATE TABLE channel_sessions (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sender_id         VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    end_user_phone    VARCHAR(20)  NOT NULL,
    session_id        VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    opened_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMPTZ,
    last_inbound_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_outbound_at  TIMESTAMPTZ,
    close_reason      VARCHAR(32),
    CONSTRAINT channel_sessions_status_chk
        CHECK (status IN ('OPEN','CLOSED'))
);

-- Partial unique so a (sender, end_user) can have at most one OPEN row,
-- but unbounded CLOSED rows (every prior session is preserved for audit).
CREATE UNIQUE INDEX channel_sessions_open_unique_idx
    ON channel_sessions(sender_id, end_user_phone)
    WHERE status = 'OPEN';

CREATE INDEX channel_sessions_session_id_idx
    ON channel_sessions(session_id);

CREATE TABLE inbound_messages (
    message_sid             VARCHAR(64)  PRIMARY KEY,
    tenant_id               VARCHAR(64)  NOT NULL,
    sender_id               VARCHAR(64)  NOT NULL,
    end_user_phone          VARCHAR(20)  NOT NULL,
    received_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    body_redacted           TEXT,
    num_media               INT          NOT NULL DEFAULT 0,
    raw_payload_json        JSONB        NOT NULL,
    media_transcripts_json  JSONB,
    processed_at            TIMESTAMPTZ,
    processing_error        TEXT
);

-- Sweepers (operator-level admin tools) trim inbound_messages older than the
-- 24h dedup window; the index keeps that cheap.
CREATE INDEX inbound_messages_received_at_idx
    ON inbound_messages(received_at);
CREATE INDEX inbound_messages_sender_phone_idx
    ON inbound_messages(sender_id, end_user_phone, received_at DESC);

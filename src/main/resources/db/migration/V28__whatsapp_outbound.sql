-- W5 — outbound side of the WhatsApp channel. Two tables per design §13:
--   outbound_messages  → keyed by Twilio MessageSid; recorder of every send
--                        the platform has made. Delivery callbacks update
--                        the same row.
--   outbound_pending   → parked replies outside the 24h service window;
--                        released on the next inbound from the same end user.

CREATE TABLE outbound_messages (
    message_sid       VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    sender_id         VARCHAR(64)  NOT NULL,
    end_user_phone    VARCHAR(20)  NOT NULL,
    session_id        VARCHAR(64),
    sent_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    body              TEXT,
    template_id       BIGINT,
    status            VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    delivered_at      TIMESTAMPTZ,
    failed_at         TIMESTAMPTZ,
    failure_code      VARCHAR(32),
    failure_message   TEXT,
    retry_count       INT          NOT NULL DEFAULT 0,
    CONSTRAINT outbound_messages_status_chk
        CHECK (status IN ('QUEUED','SENT','DELIVERED','READ','FAILED','UNDELIVERED'))
);

CREATE INDEX outbound_messages_sender_phone_idx
    ON outbound_messages(sender_id, end_user_phone, sent_at DESC);
CREATE INDEX outbound_messages_session_idx
    ON outbound_messages(session_id);

CREATE TABLE outbound_pending (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    sender_id         VARCHAR(64)  NOT NULL,
    end_user_phone    VARCHAR(20)  NOT NULL,
    session_id        VARCHAR(64)  NOT NULL,
    body              TEXT         NOT NULL,
    interactive_json  JSONB,
    queued_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    released_at       TIMESTAMPTZ,
    UNIQUE(sender_id, end_user_phone, session_id, queued_at)
);

CREATE INDEX outbound_pending_release_idx
    ON outbound_pending(sender_id, end_user_phone)
    WHERE released_at IS NULL;

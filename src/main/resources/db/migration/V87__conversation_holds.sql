-- Human hold (operator takeover): while a row is live for (tenant, end-user phone), inbound is acked and stored but
-- the engine never runs, so the person handling the conversation is the only one answering.
-- See docs/design/whatsapp-channel-design.md §11.3.

CREATE TABLE conversation_holds (
    tenant_id       VARCHAR(64)  NOT NULL,
    end_user_phone  VARCHAR(20)  NOT NULL,
    held_until      TIMESTAMPTZ  NOT NULL,
    reason          VARCHAR(64),
    held_from       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, end_user_phone)
);

CREATE INDEX conversation_holds_until_idx ON conversation_holds(held_until);

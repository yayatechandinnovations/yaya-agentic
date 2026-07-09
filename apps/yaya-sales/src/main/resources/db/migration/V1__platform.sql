-- S0 platform spine (design §5.5 idempotency, §5.6 outbox, decision 5 schema
-- isolation). Everything lives in the yaya_sales schema (flyway default-schema);
-- no table here or in any later migration may reference another schema.

-- Idempotency: (store, key) -> first response. request_hash catches the same
-- key replayed with a different body (a caller bug -> 409, never a stale reply).
CREATE TABLE idempotency_keys (
    store_id      VARCHAR(64)  NOT NULL,
    idem_key      VARCHAR(200) NOT NULL,
    request_hash  VARCHAR(64)  NOT NULL,
    response_json JSONB,
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (store_id, idem_key)
);

-- Transactional outbox: appended in the same tx as the state change, relayed
-- at-least-once, consumers dedupe on id.
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    store_id      VARCHAR(64),
    aggregate     VARCHAR(64)  NOT NULL,
    aggregate_id  VARCHAR(64),
    event_type    VARCHAR(128) NOT NULL,
    payload_json  JSONB        NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox (status, created_at);

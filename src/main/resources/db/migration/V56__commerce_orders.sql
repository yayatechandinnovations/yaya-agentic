-- Commerce chat (ecommerce-chat-design.md §11, §14): a local record of checkout
-- outcomes. yaya-agentic is not the system of record for fulfilment — this row
-- tracks the PSP order ref + status so the turn is auditable and (later) a
-- webhook can flip PENDING → PAID. amount_minor is the authoritative total.
CREATE TABLE commerce_orders (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    session_id     UUID         NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,
    provider       VARCHAR(32)  NOT NULL,
    order_ref      VARCHAR(128) NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    currency       VARCHAR(8)   NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_commerce_orders_session ON commerce_orders (session_id);
CREATE INDEX idx_commerce_orders_tenant ON commerce_orders (tenant_id);

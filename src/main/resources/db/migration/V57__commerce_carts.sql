-- Commerce chat (ecommerce-chat-design.md §7.3, §14): durable projection of the
-- session cart. Redis / working memory is the hot path (idle-TTL); this table is
-- the durable fallback so a returning shopper reconnects to the same cart and the
-- PSP webhook can clear it even after the working-memory TTL has lapsed. Also the
-- data source for abandoned-cart recovery.
CREATE TABLE commerce_carts (
    session_id   UUID         NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    cart_json    JSONB        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id)
);

CREATE INDEX idx_commerce_carts_tenant ON commerce_carts (tenant_id);

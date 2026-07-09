-- S2: channels & regions — the selling context (design §6.1). A checkout
-- resolves to exactly one (channel, region); currency, tax treatment, tenders,
-- zones and price list all follow from it.

CREATE TABLE sales_channels (
    id       VARCHAR(64)  PRIMARY KEY,
    store_id VARCHAR(64)  NOT NULL REFERENCES stores (id),
    kind     VARCHAR(16)  NOT NULL,           -- WEB_CHAT | WHATSAPP | SMS | POS
    label    VARCHAR(200) NOT NULL,
    active   BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_channels_store ON sales_channels (store_id);

CREATE TABLE regions (
    id            VARCHAR(64)  PRIMARY KEY,
    store_id      VARCHAR(64)  NOT NULL REFERENCES stores (id),
    name          VARCHAR(200) NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    tax_json      JSONB        NOT NULL,      -- { inclusive, rateBps, label }
    tenders_json  JSONB        NOT NULL,      -- allowed TenderKinds (§8.3)
    zone_ids_json JSONB,                      -- shipping zones (S5)
    price_list_id VARCHAR(64)
);
CREATE INDEX idx_regions_store ON regions (store_id);

-- Per-channel product publishing (§6.1): a product can be on WhatsApp but not POS.
CREATE TABLE channel_product_availability (
    channel_id VARCHAR(64) NOT NULL REFERENCES sales_channels (id),
    product_id VARCHAR(64) NOT NULL REFERENCES products (id),
    published  BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (channel_id, product_id)
);

-- S1: stores (selling-context config subset: store + locations) and catalog
-- (products/variants authoring). Channels/regions land in S2 (design §6.1).
-- Every row is store_id-scoped (decision 7); tenant_id is a REFERENCE to the
-- yaya-agentic tenant, never a cross-schema FK (decision 5).

CREATE TABLE stores (
    id               VARCHAR(64)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    display_name     VARCHAR(200) NOT NULL,
    default_currency VARCHAR(3)   NOT NULL,
    policies_json    JSONB        NOT NULL,   -- deduction_point, backorder, soft_ttl, checkout_ttl
    branding_json    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_stores_tenant ON stores (tenant_id);

CREATE TABLE locations (
    id           VARCHAR(64)  PRIMARY KEY,
    store_id     VARCHAR(64)  NOT NULL REFERENCES stores (id),
    label        VARCHAR(200) NOT NULL,
    kind         VARCHAR(16)  NOT NULL,       -- WAREHOUSE | SHOP | VIRTUAL
    address_json JSONB,
    sellable     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_locations_store ON locations (store_id);

CREATE TABLE catalogs (
    id            VARCHAR(64)  PRIMARY KEY,
    store_id      VARCHAR(64)  NOT NULL REFERENCES stores (id),
    version       INT          NOT NULL DEFAULT 1,
    display_name  VARCHAR(200) NOT NULL,
    status        VARCHAR(16)  NOT NULL,      -- DRAFT | PUBLISHED | DEPRECATED
    created_at    TIMESTAMPTZ  NOT NULL,
    deprecated_at TIMESTAMPTZ
);
CREATE INDEX idx_catalogs_store ON catalogs (store_id);

CREATE TABLE products (
    id              VARCHAR(64)  PRIMARY KEY,
    store_id        VARCHAR(64)  NOT NULL REFERENCES stores (id),
    catalog_id      VARCHAR(64)  NOT NULL REFERENCES catalogs (id),
    title           VARCHAR(300) NOT NULL,
    description     TEXT,
    category        VARCHAR(200),
    attributes_json JSONB,
    media_json      JSONB,
    metadata_json   JSONB,                    -- merchant custom fields (§2.3)
    status          VARCHAR(16)  NOT NULL,    -- DRAFT | ACTIVE | ARCHIVED
    sort_order      INT          NOT NULL DEFAULT 0,
    search_tsv      TSVECTOR GENERATED ALWAYS AS (
                        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, ''))
                    ) STORED,                 -- consumed by the search module later
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_products_catalog ON products (catalog_id);
CREATE INDEX idx_products_store_status ON products (store_id, status);
CREATE INDEX idx_products_search ON products USING GIN (search_tsv);

CREATE TABLE variants (
    id           VARCHAR(64)  PRIMARY KEY,
    product_id   VARCHAR(64)  NOT NULL REFERENCES products (id),
    sku          VARCHAR(100) NOT NULL,
    label        VARCHAR(200),
    price_minor  BIGINT       NOT NULL,       -- authoritative LIST price; pricing may override (§6.2)
    currency     VARCHAR(3)   NOT NULL,
    options_json JSONB,
    weight_grams INT,
    barcode      VARCHAR(100),
    UNIQUE (product_id, sku)
);
CREATE INDEX idx_variants_product ON variants (product_id);
CREATE INDEX idx_variants_sku ON variants (sku);

-- Commerce chat (ecommerce-chat-design.md §7.1, §14): a ProductCatalog is a
-- KnowledgeSource-shaped, tenant-scoped, versioned primitive. location_json holds
-- the sealed CatalogLocation (Inline | HttpApi | Database); access_json gates the
-- whole catalog (ineligible catalogs are dropped from search — existence not
-- leaked). Mirrors the profiles table conventions exactly.
CREATE TABLE product_catalogs (
    id             VARCHAR(128) NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version        INTEGER      NOT NULL DEFAULT 1,
    display_name   VARCHAR(255) NOT NULL,
    location_json  JSONB        NOT NULL,
    sync_json      JSONB,
    search_json    JSONB,
    access_json    JSONB,
    currency       VARCHAR(8)   NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deprecated_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id, version)
);

CREATE INDEX idx_product_catalogs_tenant ON product_catalogs (tenant_id);

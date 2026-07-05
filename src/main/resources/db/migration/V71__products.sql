-- Commerce redesign (commerce-redesign-authoring-rendering-paypal.md Pillar A):
-- managed product authoring at scale. Products move OUT of the versioned
-- product_catalogs.location_json blob into their own MUTABLE table so:
--   * editing one field is a single-row UPDATE, not a catalog version bump;
--   * the admin can page/search/sort over hundreds of rows;
--   * CSV import/export becomes a real bulk path.
-- Catalog *config* stays versioned (product_catalogs); product *data* is
-- operational and lives here. A catalog whose location is Database(logicalName)
-- draws its products from this table via DatabaseCatalogLoader.
--
-- product_id is the merchant SKU/code (stable, human-authored). Rows are
-- ARCHIVED, never hard-deleted, so a product referenced by a past cart/order
-- never dangles.
CREATE TABLE products (
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_id        VARCHAR(128) NOT NULL,          -- product_catalogs.id (logical, version-independent)
    product_id        VARCHAR(128) NOT NULL,          -- merchant SKU / code
    title             VARCHAR(512) NOT NULL,
    short_description TEXT,
    long_description  TEXT,
    price_minor       BIGINT       NOT NULL,          -- integer minor units — never float (#1)
    currency          VARCHAR(8)   NOT NULL,
    category          VARCHAR(255),
    attributes_json   JSONB        NOT NULL DEFAULT '{}',   -- facet source: {"ingredient":"castor oil", ...}
    variants_json     JSONB        NOT NULL DEFAULT '[]',   -- [{variantId,label,priceMinor,sku,availability}]
    image_urls_json   JSONB        NOT NULL DEFAULT '[]',
    availability      VARCHAR(16)  NOT NULL DEFAULT 'IN_STOCK',  -- IN_STOCK|LOW|OUT|PREORDER
    inventory         INTEGER,                        -- nullable; drives availability when tracked
    detail_url        VARCHAR(1024),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE|ARCHIVED (archive != delete)
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, catalog_id, product_id)
);

-- Admin list + shopper enumeration: active products of a catalog, in author order.
CREATE INDEX products_catalog_status_ix ON products (tenant_id, catalog_id, status, sort_order);
-- Attribute-containment filtering (facet chips) without a per-row scan.
CREATE INDEX products_attrs_ix ON products USING GIN (attributes_json jsonb_path_ops);

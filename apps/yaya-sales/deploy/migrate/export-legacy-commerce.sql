-- ============================================================
--  export-legacy-commerce.sql — one-shot per tenant (design §12.2)
-- ============================================================
--
--  Exports the legacy in-core commerce data (renamed to legacy_* by the
--  agent's V73__decommission_commerce.sql) into CSV files that
--  import-legacy-commerce.sh feeds to the yaya-sales admin API.
--
--  Run against the AGENT database, FROM the directory the CSVs should land in
--  (\copy writes relative to psql's cwd):
--
--    cd /path/to/export-dir
--    psql "$AGENT_DB_URL" -v tenant_id='<tenantId>' \
--         -f /path/to/export-legacy-commerce.sql
--
--  Produces:
--    legacy_products.csv         → sales CSV bulk-import shape (imported)
--    legacy_opening_balance.csv  → sku,on_hand for the opening-balance seed (imported)
--    legacy_catalogs.csv         → catalog names/currencies (reference for store/catalog setup)
--    legacy_psp_accounts.csv     → PSP connections (reference — re-onboard via yaya-sales, RUNBOOK step 7)
--    legacy_orders_archive.csv   → order history archive (NOT imported — see below)
--
--  Read-only: this script never mutates the agent database.
--
--  DELIBERATELY SKIPPED:
--    * legacy_commerce_carts — carts are ephemeral session state; shoppers
--      simply rebuild them. Nothing to migrate.
--    * legacy_commerce_orders — historical orders are NOT imported into
--      yaya-sales (its order lifecycle starts fresh); they stay queryable in
--      legacy_commerce_orders until the final drop-legacy-commerce.sql, and
--      legacy_orders_archive.csv preserves them beyond that.

\set ON_ERROR_STOP on

-- ------------------------------------------------------------
-- Products → sales CSV bulk-import shape.
--
-- Sales header: product_id,title,description,category,status,sku,
--               variant_label,price_minor,currency,weight_grams,barcode
--
--  * product_id is left EMPTY on purpose: in the sales CSV it means "existing
--    sales ProductId to update". New products group by title, one row per
--    variant, keyed by sku.
--  * Legacy product_id (the merchant SKU) becomes the variant sku for
--    products without variants; variant rows use the legacy variant's
--    sku/variantId ({variantId,label,priceMinor,sku,availability}).
--  * Only status='ACTIVE' products are exported — widen the WHERE below if
--    you want ARCHIVED history in yaya-sales too (status maps 1:1).
--  * weight_grams/barcode did not exist in the legacy model → empty.
--  * Newlines are flattened out of text fields: the sales importer splits the
--    file into lines before RFC-4180 parsing, so embedded newlines would
--    break rows.
-- ------------------------------------------------------------
CREATE TEMP VIEW export_products AS
WITH flat AS (
    -- one row per variant …
    SELECT p.product_id                                            AS legacy_id,
           p.title,
           COALESCE(NULLIF(p.short_description, ''), p.long_description) AS description,
           p.category,
           p.status,
           COALESCE(NULLIF(v.value ->> 'sku', ''), v.value ->> 'variantId') AS sku,
           v.value ->> 'label'                                     AS variant_label,
           COALESCE((v.value ->> 'priceMinor')::bigint, p.price_minor) AS price_minor,
           p.currency,
           p.sort_order
    FROM legacy_products p
    CROSS JOIN LATERAL jsonb_array_elements(p.variants_json) AS v(value)
    WHERE p.tenant_id = :'tenant_id'
      AND p.status = 'ACTIVE'
      AND jsonb_array_length(p.variants_json) > 0
    UNION ALL
    -- … or one row per variant-less product (legacy product_id becomes the sku)
    SELECT p.product_id,
           p.title,
           COALESCE(NULLIF(p.short_description, ''), p.long_description),
           p.category,
           p.status,
           p.product_id,
           NULL,
           p.price_minor,
           p.currency,
           p.sort_order
    FROM legacy_products p
    WHERE p.tenant_id = :'tenant_id'
      AND p.status = 'ACTIVE'
      AND jsonb_array_length(p.variants_json) = 0
)
SELECT ''                                                     AS product_id,
       regexp_replace(title,                 E'[\\n\\r]+', ' ', 'g') AS title,
       regexp_replace(COALESCE(description, ''), E'[\\n\\r]+', ' ', 'g') AS description,
       regexp_replace(COALESCE(category, ''),    E'[\\n\\r]+', ' ', 'g') AS category,
       status,
       sku,
       regexp_replace(COALESCE(variant_label, ''), E'[\\n\\r]+', ' ', 'g') AS variant_label,
       price_minor,
       currency,
       ''                                                     AS weight_grams,
       ''                                                     AS barcode
FROM flat
ORDER BY sort_order, title, sku;

-- ------------------------------------------------------------
-- Opening-balance seed (design §12.2): the yaya-sales ledger has no source
-- rows, so the import POSTs one opening-balance ADJUSTMENT per SKU from
-- today's on-hand — history starts clean, on-hand matches reality on day one.
--
-- Only variant-less products carried a countable `inventory` in the legacy
-- model (variants only had an availability enum, never a count), so only
-- those seed automatically. Variant SKUs are listed by the review query
-- below — count and adjust them by hand after the import.
-- ------------------------------------------------------------
CREATE TEMP VIEW export_opening_balance AS
SELECT p.product_id AS sku,
       p.inventory  AS on_hand
FROM legacy_products p
WHERE p.tenant_id = :'tenant_id'
  AND p.status = 'ACTIVE'
  AND p.inventory IS NOT NULL
  AND jsonb_array_length(p.variants_json) = 0
ORDER BY p.product_id;

-- Reference: catalog names + currency (drives store/catalog creation flags).
CREATE TEMP VIEW export_catalogs AS
SELECT DISTINCT ON (c.id)
       c.id, c.display_name, c.currency, c.status, c.version
FROM legacy_product_catalogs c
WHERE c.tenant_id = :'tenant_id'
ORDER BY c.id, c.version DESC;

-- Reference: PSP connections. NOT imported — merchant PSP accounts are
-- provider-side links and must be re-onboarded through yaya-sales
-- (POST /v1/sales/admin/psp/{provider}/onboarding-link, RUNBOOK step 7).
-- This CSV is the checklist of which providers were connected.
CREATE TEMP VIEW export_psp_accounts AS
SELECT provider, account_id, status, charges_enabled, tracking_ref, created_at
FROM legacy_merchant_psp_accounts
WHERE tenant_id = :'tenant_id'
ORDER BY provider;

-- Archive only: full legacy order history (see header note).
CREATE TEMP VIEW export_orders_archive AS
SELECT id, session_id, tenant_id, provider, order_ref,
       amount_minor, currency, status, created_at
FROM legacy_commerce_orders
WHERE tenant_id = :'tenant_id'
ORDER BY created_at;

\echo '==> exporting legacy commerce for tenant' :'tenant_id'

\copy (SELECT * FROM export_products)        TO 'legacy_products.csv'        WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM export_opening_balance) TO 'legacy_opening_balance.csv' WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM export_catalogs)        TO 'legacy_catalogs.csv'        WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM export_psp_accounts)    TO 'legacy_psp_accounts.csv'    WITH (FORMAT csv, HEADER)
\copy (SELECT * FROM export_orders_archive)  TO 'legacy_orders_archive.csv'  WITH (FORMAT csv, HEADER)

-- ------------------------------------------------------------
-- Post-export review queries (informational, printed to the terminal).
-- ------------------------------------------------------------

\echo ''
\echo '--- duplicate titles (the sales CSV importer groups new products by'
\echo '--- title, so these WOULD MERGE into one product — rename before import):'
SELECT title, count(*) AS products
FROM legacy_products
WHERE tenant_id = :'tenant_id' AND status = 'ACTIVE'
GROUP BY title
HAVING count(*) > 1;

\echo ''
\echo '--- variant products with a product-level inventory count (ambiguous —'
\echo '--- not auto-seeded; count per variant SKU and adjust manually):'
SELECT product_id, title, inventory, jsonb_array_length(variants_json) AS variants
FROM legacy_products
WHERE tenant_id = :'tenant_id' AND status = 'ACTIVE'
  AND inventory IS NOT NULL
  AND jsonb_array_length(variants_json) > 0;

\echo ''
\echo '--- export row counts:'
SELECT 'products.csv rows'        AS file, count(*) FROM export_products
UNION ALL
SELECT 'opening_balance.csv rows',        count(*) FROM export_opening_balance
UNION ALL
SELECT 'catalogs.csv rows',               count(*) FROM export_catalogs
UNION ALL
SELECT 'psp_accounts.csv rows',           count(*) FROM export_psp_accounts
UNION ALL
SELECT 'orders_archive.csv rows',         count(*) FROM export_orders_archive;

\echo ''
\echo '==> done. Next: import-legacy-commerce.sh (see docs/RUNBOOK.md step 6).'

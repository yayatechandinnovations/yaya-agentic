-- Commerce decommission (yaya-sales design §12.4 S4): the in-core commerce
-- subsystem is deleted; catalog/cart/order/PSP data now lives in yaya-sales.
--
-- The legacy tables are RENAMED, not dropped, so tenant data stays recoverable
-- for the one-shot per-tenant export into yaya-sales (§12.2). A later migration
-- drops the legacy_* tables once that export has run.
--
-- profiles.commerce_json is intentionally untouched: it now carries the thin
-- CommerceBinding, and legacy CommerceConfig rows are read leniently.

ALTER TABLE IF EXISTS products              RENAME TO legacy_products;
ALTER TABLE IF EXISTS product_catalogs      RENAME TO legacy_product_catalogs;
ALTER TABLE IF EXISTS commerce_carts        RENAME TO legacy_commerce_carts;
ALTER TABLE IF EXISTS commerce_orders       RENAME TO legacy_commerce_orders;
ALTER TABLE IF EXISTS merchant_psp_accounts RENAME TO legacy_merchant_psp_accounts;

-- Renaming a table keeps its indexes; rename them too so nothing squats on the
-- old names if a future (post-export) schema ever reintroduces them.
ALTER INDEX IF EXISTS products_catalog_status_ix          RENAME TO legacy_products_catalog_status_ix;
ALTER INDEX IF EXISTS products_attrs_ix                   RENAME TO legacy_products_attrs_ix;
ALTER INDEX IF EXISTS idx_product_catalogs_tenant         RENAME TO legacy_idx_product_catalogs_tenant;
ALTER INDEX IF EXISTS idx_commerce_carts_tenant           RENAME TO legacy_idx_commerce_carts_tenant;
ALTER INDEX IF EXISTS idx_commerce_orders_session         RENAME TO legacy_idx_commerce_orders_session;
ALTER INDEX IF EXISTS idx_commerce_orders_tenant          RENAME TO legacy_idx_commerce_orders_tenant;
ALTER INDEX IF EXISTS merchant_psp_accounts_tracking_ix   RENAME TO legacy_merchant_psp_accounts_tracking_ix;

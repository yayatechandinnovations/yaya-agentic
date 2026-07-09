-- ============================================================
--  drop-legacy-commerce.sql — THE FINAL, MANUAL cleanup
-- ============================================================
--
--  Drops the legacy in-core commerce tables from the AGENT database
--  (renamed to legacy_* by V73__decommission_commerce.sql).
--
--  ┌─────────────────────────────────────────────────────────────────┐
--  │  RUN ONLY AFTER the import is VERIFIED (docs/RUNBOOK.md step 9):│
--  │    * product count in yaya-sales matches the export             │
--  │    * on-hand per SKU equals legacy inventory                    │
--  │    * a test checkout succeeded in the playground                │
--  │    * legacy_orders_archive.csv is safely stored — this drop     │
--  │      deletes the historical order lookup                        │
--  │                                                                 │
--  │  This is DELIBERATELY NOT a Flyway migration: it must never run │
--  │  automatically, so a deploy can never destroy the legacy data   │
--  │  before the export/import happened. It is destructive for ALL  │
--  │  tenants — if any tenant has not migrated yet, DO NOT run it.  │
--  │                                                                 │
--  │  Take a backup first:                                           │
--  │    pg_dump "$AGENT_DB_URL" \                                    │
--  │      -t legacy_products -t legacy_product_catalogs \            │
--  │      -t legacy_commerce_carts -t legacy_commerce_orders \       │
--  │      -t legacy_merchant_psp_accounts \                          │
--  │      -f legacy_commerce_backup.sql                              │
--  │                                                                 │
--  │  Then: psql "$AGENT_DB_URL" -f drop-legacy-commerce.sql         │
--  └─────────────────────────────────────────────────────────────────┘
--
--  Rollback note: until this script runs, the legacy_* tables are the
--  rollback path — the old data is intact and V73 can be reversed by
--  renaming the tables back. After this script, the only recovery is the
--  pg_dump backup above.

\set ON_ERROR_STOP on

BEGIN;

DROP TABLE IF EXISTS legacy_products;
DROP TABLE IF EXISTS legacy_product_catalogs;
DROP TABLE IF EXISTS legacy_commerce_carts;
DROP TABLE IF EXISTS legacy_commerce_orders;
DROP TABLE IF EXISTS legacy_merchant_psp_accounts;

COMMIT;

\echo '==> legacy commerce tables dropped. The cutover is complete.'

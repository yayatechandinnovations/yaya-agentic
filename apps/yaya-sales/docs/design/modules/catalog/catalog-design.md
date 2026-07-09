# yaya-sales · catalog — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.catalog` (§6 module map, row 1)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §5.1 (product/variant model), §6 (module map), §9 (data model), §10.1 (admin API).
**Consumes:** pricing (prices live on variants but are the pricing module's concern), inventory (availability), search (feeds the index), stores (channel publishing).
**Consumed by:** cart, orders, search, integration.

---

## 1. Purpose

Own the merchandising model — products, their purchasable **variants/SKUs**, categories/collections, media, and the authoring workflow (CRUD, bulk CSV, publish/versioning). Prices live *on* variants but are computed by **pricing** (§6.2); stock lives *per SKU × location* in **inventory** (§7). Catalog owns the *shape and lifecycle* of what's for sale, not its price or stock.

## 2. Scope

**IN:** product/variant/option model; categories & collections; media references; product status (`DRAFT|ACTIVE|ARCHIVED`); per-product CRUD; bulk CSV import/export with row-level errors; catalog grouping + versioning (publish snapshots the active set); `metadata_json` custom fields; per-channel publishing hook (availability flag, owned with stores §6.1).

**OUT:** pricing/tax (→ pricing), stock/availability (→ inventory), search ranking/facets (→ search), the channel/region config itself (→ stores).

## 3. Responsibilities & key types

```java
record Product(ProductId id, StoreId store, CatalogId catalog, String title, String description,
               List<MediaRef> media, List<Variant> variants, Map<String,String> attributes,
               String category, ProductStatus status, Map<String,Object> metadata,
               int sortOrder, Instant createdAt, Instant updatedAt) {}
record Variant(VariantId id, String sku, String label, Money price, Map<String,String> options) {}
enum ProductStatus { DRAFT, ACTIVE, ARCHIVED }
```

- Prices are the variant's authoritative *list* price; pricing may override per region/channel/customer-group.
- Publishing: a `Catalog` is a versioned collection; publishing bumps `version` and snapshots the active product set (mirrors the parent's catalog-versioning note).

## 4. Data model (owned tables)

`catalogs`, `products`, `variants`, `channel_product_availability` — see parent §9. `products.metadata_json` holds merchant custom fields (convention across products/orders/customers).

## 5. API surface (§10.1)

`POST/GET/PUT /v1/sales/admin/catalogs[/{id}]`, `…/catalogs/{id}/products[/{pid}]`, `…/products/{pid}/archive|restore`, `…/products/bulk` (CSV), `…/products/export.csv`, `POST /v1/sales/admin/media`.

## 6. SPIs

- `CatalogLoader { List<Product> load(CatalogId) }` — inline | http | database sources (v1: database is the managed table).

## 7. Invariants

- A variant's `sku` is unique within its product; a product belongs to exactly one catalog version.
- Archiving is reversible and never deletes history; archived products round-trip through CSV export.

## 8. Open questions (module-local)

- **TBD:** typed attribute schemas (enum/range validation) vs the current free `attributes` map — parent scope table lists this as vNext.
- **TBD:** media pipeline (resize/derivatives) — reuse platform media (§5.13-eq) or defer.

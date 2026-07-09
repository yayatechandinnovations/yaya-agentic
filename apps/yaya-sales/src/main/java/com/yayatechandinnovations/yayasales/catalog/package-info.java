/**
 * Merchandising (module design {@code modules/catalog/}): products, variants/
 * SKUs, categories, media refs, authoring lifecycle (CRUD, archive, CSV,
 * publish). Owns the SHAPE of what's for sale — prices are computed by pricing
 * (§6.2) and stock lives in inventory (§7); the variant carries only the
 * authoritative list price.
 */
package com.yayatechandinnovations.yayasales.catalog;

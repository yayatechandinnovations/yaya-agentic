package com.yayatechandinnovations.yayasales.catalog;

import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;

import java.util.List;

/**
 * CSV bulk authoring (module design §2): one row = one variant, upserted by
 * SKU, with row-level errors — a bad row never aborts the batch.
 */
public interface BulkImport {

    Result importCsv(CatalogId catalog, String csv);

    String exportCsv(CatalogId catalog);

    record Result(int created, int updated, List<RowError> errors) {}

    record RowError(int row, String message) {}
}

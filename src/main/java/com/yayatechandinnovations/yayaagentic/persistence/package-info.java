/**
 * JPA entities + Spring Data repositories backing the M1 admin and registry
 * SPIs. Schema is owned by {@code db/migration/V*.sql}; Hibernate runs in
 * {@code validate} mode, so structural drift fails at startup.
 * <p>
 * jsonb columns are mapped as {@code String} with
 * {@code @JdbcTypeCode(SqlTypes.JSON)} — we own JSON shape at the SPI layer
 * (records / sealed types) and don't want Hibernate's Map<String,Object>
 * coercion at the boundary.
 */
package com.yayatechandinnovations.yayaagentic.persistence;

package com.yayatechandinnovations.yayaagentic.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * A commerce profile's pointer at its yaya-sales store (yaya-sales design
 * §12.3). The store's catalogs/currency/PSP/policies live in yaya-sales; the
 * agent keeps only what it needs to route {@code sales.*} HTTP tools and gate
 * cart UX.
 *
 * <p>Deserialization ignores unknown fields on purpose: legacy
 * {@code profiles.commerce_json} rows carry the retired in-core
 * {@code CommerceConfig} shape (catalogId, currency, paymentProvider, …).
 * Reads map what maps ({@code cartEnabled}, {@code checkoutTool});
 * {@code storeId} is absent in legacy rows and stays null. Writes always emit
 * this shape.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommerceBinding(String storeId, Ids.ToolId checkoutTool, boolean cartEnabled) {}

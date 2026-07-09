/**
 * Returns & reverse logistics (module design {@code modules/returns/}, parent
 * §8.8): authorize an RMA against a paid order's lines, capture the return
 * shipment metadata, and on receipt put stock back (RESTOCK only — damaged
 * goods never re-enter the ledger) and money out through the orders refund
 * path. Orchestrates orders / inventory / payments; owns none of their rules.
 */
package com.yayatechandinnovations.yayasales.returns;

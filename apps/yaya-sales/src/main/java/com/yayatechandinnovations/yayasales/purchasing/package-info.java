/**
 * Purchasing — the stock-in end of the loop (module design
 * {@code modules/purchasing/}, parent §9, §7.5): suppliers, purchase orders,
 * and goods receipts. A receipt line posts exactly one RECEIPT movement with
 * its unit cost through the inventory ledger, which is what feeds the
 * moving-average valuation and, ultimately, the margin report.
 */
package com.yayatechandinnovations.yayasales.purchasing;

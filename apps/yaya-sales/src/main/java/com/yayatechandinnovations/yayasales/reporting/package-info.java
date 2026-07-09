/**
 * Reporting — read-only business insight (module design
 * {@code modules/reporting/}, parent §6 row 14, §7.5, §10.1): sales, inventory
 * valuation, margin (revenue − COGS drawn from the SALE movements' moving-
 * average cost), low stock, and COD cash reconciliation. Never mutates domain
 * state; aggregates over the sibling modules' tables go through read-only
 * JdbcTemplate queries (never their internal classes), everything else through
 * published read APIs. Figures reconcile to the ledgers they project.
 */
package com.yayatechandinnovations.yayasales.reporting;

/**
 * The core module (module design {@code modules/inventory/}, parent §5.1–5.3,
 * §7): an append-only stock-movement LEDGER as the source of truth, a
 * same-transaction summary projection as the fast read + concurrency guard,
 * and time-boxed reservations so nothing oversells. Answers authoritatively:
 * how many can I sell, what happened to a unit, what is my stock worth?
 */
package com.yayatechandinnovations.yayasales.inventory;

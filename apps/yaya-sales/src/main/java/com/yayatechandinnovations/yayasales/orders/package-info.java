/**
 * The order aggregate + lifecycle (module design {@code modules/orders/},
 * parent §8.1–8.2): the FSM, the append-only event log, and the checkout saga
 * that turns a validated cart into a paid order with compensations at every
 * step. Transitions are driven by tenders and fulfillment — never by a raw
 * API mutation.
 */
package com.yayatechandinnovations.yayasales.orders;

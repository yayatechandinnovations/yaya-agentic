package com.yayatechandinnovations.yayasales.payments;

/**
 * §8.3 — a means of payment on an order. Which kinds are OFFERED is per-region
 * configuration (§6.1): COD on WhatsApp, card on web, is data — not a special
 * case.
 */
public enum TenderKind { PSP, COD, MANUAL, STORE_CREDIT }

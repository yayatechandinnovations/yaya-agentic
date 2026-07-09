package com.yayatechandinnovations.yayasales.orders.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

/**
 * §8.1 — a draft-send line lost the stock race. Thrown INSIDE the prepare
 * transaction so the rollback releases every hold the send had already taken;
 * the caller turns it into the checkout {@code Insufficient} outcome and the
 * draft stays exactly as it was.
 */
class DraftStockInsufficientException extends RuntimeException {

    private final VariantId sku;
    private final long available;

    DraftStockInsufficientException(VariantId sku, long available) {
        super("insufficient stock for " + sku.value() + ": " + available + " available");
        this.sku = sku;
        this.available = available;
    }

    VariantId sku() {
        return sku;
    }

    long available() {
        return available;
    }
}

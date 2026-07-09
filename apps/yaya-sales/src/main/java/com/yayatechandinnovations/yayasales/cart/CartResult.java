package com.yayatechandinnovations.yayasales.cart;

import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

/**
 * A cart mutation outcome. {@code Insufficient} is a normal answer, not an
 * error — the agent tells the shopper what IS available.
 */
public sealed interface CartResult permits CartResult.Ok, CartResult.Insufficient {

    record Ok(CartView cart) implements CartResult {}

    record Insufficient(VariantId sku, long available, CartView cart) implements CartResult {}
}

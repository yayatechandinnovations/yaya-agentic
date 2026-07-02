package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;

/**
 * What the framework hands a {@link PaymentProvider} to open a checkout. It
 * carries the authoritative {@link Cart} (its {@code subtotal} is the merchant's
 * / catalog's word, never the model's) and the merchant's PSP sub-account, if
 * any. It deliberately carries NO card data — the framework never sits in the
 * flow of card details. See {@code ecommerce-chat-design.md} §11.
 */
public record CheckoutRequest(
        Ids.TenantId tenant,
        Ids.SessionId session,
        String principalSubject,
        String connectedAccountId,
        Cart cart,
        String currency,
        Map<String, Object> fulfilment
) {
    public CheckoutRequest {
        fulfilment = fulfilment == null ? Map.of() : Map.copyOf(fulfilment);
    }
}

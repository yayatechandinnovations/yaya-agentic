package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

/**
 * §8.3 / §17 — the PSP tender's engine (dlocal | stripe | paypal | sandbox).
 * The real provider adapters port over from the in-core {@code commerce/payment}
 * at S4; the SPI shape is fixed here.
 */
public interface PaymentProvider {

    String name();

    AuthorizeOutcome authorize(AuthorizeRequest request);

    RefundOutcome refund(RefundRequest request);

    record AuthorizeRequest(StoreId store, OrderId order, Money amount, String returnUrl) {}

    record RefundRequest(StoreId store, OrderId order, Money amount, String pspRef) {}

    sealed interface AuthorizeOutcome
            permits AuthorizeOutcome.HostedUrl, AuthorizeOutcome.Intent,
                    AuthorizeOutcome.Captured, AuthorizeOutcome.Failed {
        record HostedUrl(String url, String pspRef) implements AuthorizeOutcome {}
        /** Stripe's drop-in path: the client mounts the PSP sheet with the secret. */
        record Intent(String clientSecret, String publishableKey, String pspRef)
                implements AuthorizeOutcome {}
        record Captured(String pspRef) implements AuthorizeOutcome {}
        record Failed(String reason) implements AuthorizeOutcome {}
    }

    sealed interface RefundOutcome permits RefundOutcome.Refunded, RefundOutcome.Failed {
        record Refunded(String pspRef) implements RefundOutcome {}
        record Failed(String reason) implements RefundOutcome {}
    }
}

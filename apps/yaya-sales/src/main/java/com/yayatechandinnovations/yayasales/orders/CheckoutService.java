package com.yayatechandinnovations.yayasales.orders;

import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

/**
 * §8.2 — the checkout saga. One call, compensations inside: any failing step
 * releases what it firmed, voids what it began, and cancels what it created —
 * no orphan reservation, no phantom order (§14).
 */
public interface CheckoutService {

    CheckoutOutcome checkout(CheckoutCommand command);

    /**
     * @param applyStoreCredit split tender (§8.3): redeem available credit
     *        first, collect the remainder via {@code tender}.
     * @param idempotencyKey required — a retried checkout re-attaches to the
     *        same order rather than re-reserving twice (§5.5).
     * @param shipTo optional (§8.2 steps 1/2a-b): when present the address is
     *        validated (unservable fails before any reservation firms), the
     *        chosen {@code deliveryMethodId} is quoted into the pricing
     *        context's shipping, and {@code slotId} — if given — books a
     *        hard-capacity slot (released on any later failure).
     */
    record CheckoutCommand(
            CartId cart,
            TenderKind tender,
            boolean applyStoreCredit,
            String returnUrl,
            String couponCode,
            String customerRef,
            String idempotencyKey,
            Address shipTo,
            DeliveryMethodId deliveryMethodId,
            SlotId slotId) {

        /** Delivery-less checkout — behaves exactly as before S5. */
        public CheckoutCommand(CartId cart, TenderKind tender, boolean applyStoreCredit,
                               String returnUrl, String couponCode, String customerRef,
                               String idempotencyKey) {
            this(cart, tender, applyStoreCredit, returnUrl, couponCode, customerRef,
                    idempotencyKey, null, null, null);
        }
    }

    sealed interface CheckoutOutcome
            permits CheckoutOutcome.HostedUrl, CheckoutOutcome.PaymentIntent,
                    CheckoutOutcome.Placed, CheckoutOutcome.Paid, CheckoutOutcome.Insufficient {

        /** PSP: shopper completes payment at the URL; the webhook flips PAID. */
        record HostedUrl(OrderView order, String url) implements CheckoutOutcome {}

        /** PSP drop-in (Stripe): the client mounts the sheet with the secret; the webhook flips PAID. */
        record PaymentIntent(OrderView order, String clientSecret, String publishableKey)
                implements CheckoutOutcome {}

        /** COD / MANUAL: order placed PENDING_PAYMENT, money moves later. */
        record Placed(OrderView order, String instructions) implements CheckoutOutcome {}

        /** Fully settled at checkout (store credit covered it all). */
        record Paid(OrderView order) implements CheckoutOutcome {}

        /** A line failed revalidation against live stock (§8.2 step 2). */
        record Insufficient(VariantId sku, long available) implements CheckoutOutcome {}
    }
}

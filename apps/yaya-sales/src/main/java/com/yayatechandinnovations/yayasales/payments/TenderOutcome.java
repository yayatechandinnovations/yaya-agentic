package com.yayatechandinnovations.yayasales.payments;

/**
 * §8.3 — what beginning a tender yields. PSPs hand back somewhere to pay;
 * COD/manual owe nothing at checkout; store credit settles on the spot.
 */
public sealed interface TenderOutcome
        permits TenderOutcome.HostedUrl, TenderOutcome.PaymentIntent,
                TenderOutcome.NothingDueNow, TenderOutcome.SettledInstantly {

    /** PSP: the shopper completes payment at this URL; the webhook settles. */
    record HostedUrl(String url, String pspRef) implements TenderOutcome {}

    /** PSP drop-in (Stripe): the client mounts the sheet with the secret; the webhook settles. */
    record PaymentIntent(String clientSecret, String publishableKey, String pspRef)
            implements TenderOutcome {}

    /** COD (courier collects at the door) or MANUAL (merchant confirms the transfer). */
    record NothingDueNow(String instructions) implements TenderOutcome {}

    /** STORE_CREDIT: redeemed and settled in the same transaction. */
    record SettledInstantly() implements TenderOutcome {}
}

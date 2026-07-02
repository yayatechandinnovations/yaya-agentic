package com.yayatechandinnovations.yayaagentic.commerce.payment;

/**
 * What a {@link PaymentProvider} returns from opening a checkout — one of three
 * shapes the client knows how to complete, none of which involves the framework
 * touching a card:
 * <ul>
 *   <li>{@link HostedUrl} — open the PSP's PCI-compliant hosted payment page;</li>
 *   <li>{@link PaymentIntent} — mount the PSP drop-in with a client secret;</li>
 *   <li>{@link OrderPlaced} — order created directly (e.g. invoice / COD).</li>
 * </ul>
 * Every shape carries the {@code orderRef}, and the authoritative
 * {@code amountMinor} + {@code currency}. See {@code ecommerce-chat-design.md} §11.
 */
public sealed interface CheckoutOutcome
        permits CheckoutOutcome.HostedUrl,
                CheckoutOutcome.PaymentIntent,
                CheckoutOutcome.OrderPlaced {

    String orderRef();
    long amountMinor();
    String currency();

    record HostedUrl(String orderRef, long amountMinor, String currency, String url)
            implements CheckoutOutcome {}

    record PaymentIntent(String orderRef, long amountMinor, String currency,
                         String clientSecret, String publishableKey)
            implements CheckoutOutcome {}

    record OrderPlaced(String orderRef, long amountMinor, String currency, String status)
            implements CheckoutOutcome {}
}

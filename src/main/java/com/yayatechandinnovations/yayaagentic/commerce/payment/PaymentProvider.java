package com.yayatechandinnovations.yayaagentic.commerce.payment;

/**
 * Abstracts a licensed PSP's platform product (the same SPI discipline as
 * {@code BlobStore} / {@code Retriever}). The framework <b>orchestrates</b> —
 * assembles the order from the authoritative cart, gates it behind the confirm
 * step — while the PSP carries card data, PCI, KYC, settlement, and chargeback
 * liability. yaya-agentic is never in the flow of funds and never a processor.
 *
 * <p>Default implementation for the Dominican Republic / LatAm is
 * {@code DLocalPaymentProvider}; Stripe Connect / others swap in per region.
 * See {@code ecommerce-chat-design.md} §11 and open Q#7.</p>
 */
public interface PaymentProvider {

    /** Stable id for logging/audit (e.g. {@code "dlocal"}). */
    String name();

    /** Open a checkout for the request's cart. Never receives or returns card data. */
    CheckoutOutcome createCheckout(CheckoutRequest request);
}

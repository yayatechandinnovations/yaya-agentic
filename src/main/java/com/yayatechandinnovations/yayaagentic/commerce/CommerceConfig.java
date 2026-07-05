package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;

/**
 * The commerce trait a {@code Profile} carries to become a store. This is the
 * <i>only</i> Profile change — nullable and back-compatible: an existing profile
 * with {@code commerce == null} is simply not a store. "Is this a store?" ==
 * {@code commerce != null && commerce.cartEnabled()}. The chat entry point only
 * routes to the profile; it never decides commerce. See
 * {@code ecommerce-chat-design.md} §3, §7.4.
 */
public record CommerceConfig(
        List<Ids.ProductCatalogId> catalogs,
        String currency,
        Ids.ToolId checkoutTool,
        boolean cartEnabled,
        String connectedAccountId,  // merchant's PSP connected account (e.g. Stripe acct_…);
                                    // null = platform charge. Never sent to the client.
        String paymentProvider      // per-profile PSP override: dlocal|stripe|paypal (case-insensitive);
                                    // null/blank = the framework default (PaymentRoutingProperties).
                                    // Server-side only — never sent to the client.
) {
    public CommerceConfig {
        catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
    }

    /** Back-compat: a profile that doesn't pin a payment provider (framework default). */
    public CommerceConfig(List<Ids.ProductCatalogId> catalogs, String currency, Ids.ToolId checkoutTool,
                          boolean cartEnabled, String connectedAccountId) {
        this(catalogs, currency, checkoutTool, cartEnabled, connectedAccountId, null);
    }
}

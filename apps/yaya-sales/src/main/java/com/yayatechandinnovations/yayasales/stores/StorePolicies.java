package com.yayatechandinnovations.yayasales.stores;

/**
 * Per-store operational policy (design §5.3, decisions 2 & 6): when on-hand
 * actually drops, whether overselling is permitted by default, and how long
 * cart/checkout holds live.
 */
public record StorePolicies(
        DeductionPoint deductionPoint,
        BackorderPolicy backorder,
        int softHoldTtlSeconds,
        int checkoutHoldTtlSeconds) {

    public enum DeductionPoint { ON_PAYMENT, ON_FULFILLMENT }

    public enum BackorderPolicy { DENY, ALLOW, PREORDER }

    /** Decision 2: ON_PAYMENT default; decision 6: ~10 min soft hold. */
    public static StorePolicies defaults() {
        return new StorePolicies(DeductionPoint.ON_PAYMENT, BackorderPolicy.DENY, 600, 1800);
    }
}

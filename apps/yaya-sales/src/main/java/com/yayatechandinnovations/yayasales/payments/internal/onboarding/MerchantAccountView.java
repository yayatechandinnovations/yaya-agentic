package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

/**
 * Read model of a store's connected PSP account for the admin UI + checkout
 * routing. {@code connected} is the UI's "is this usable" signal (ACTIVE +
 * charges enabled). Design §10.1.
 */
public record MerchantAccountView(
        String provider,
        MerchantAccountStatus status,
        boolean chargesEnabled,
        String accountId,
        boolean onboardingSupported
) {
    /** Funds may route to this account only when it is ACTIVE and charges are enabled. */
    public boolean connected() {
        return status == MerchantAccountStatus.ACTIVE && chargesEnabled;
    }

    /** A provider that has no onboarding bean (e.g. dLocal) — the UI hides Connect. */
    public static MerchantAccountView unsupported(String provider) {
        return new MerchantAccountView(provider, MerchantAccountStatus.PENDING, false, null, false);
    }
}

package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.Map;

/**
 * Drives a PSP's hosted merchant-onboarding so a store owner can connect their
 * PayPal/Stripe account from inside the app — no API keys, no dashboards. Sibling
 * to {@code PaymentProvider} (a PSP can charge without self-serve onboarding, e.g.
 * dLocal, and vice-versa), same sandbox-first discipline. Design §8.3, §10.1.
 *
 * <p>Never receives card data or platform secrets client-side: {@link #start}
 * returns only a hosted URL the browser opens; the connected-account id is
 * captured server-side on {@link #reconcile}.</p>
 */
public interface MerchantOnboarding {

    /** Matches {@code PaymentProvider.name()} — {@code "paypal"} | {@code "stripe"}. */
    String provider();

    /** True when live credentials are wired; false = sandbox (fabricated flow). */
    boolean isConfigured();

    /** Begin onboarding for a store: returns a hosted URL to open + our tracking ref. */
    OnboardingSession start(OnboardingStart start);

    /** Reconcile a return redirect or webhook payload into a normalized account view. */
    ReconcileResult reconcile(OnboardingCallback callback);

    /** What the service hands {@link #start}: the store + where the PSP returns the merchant. */
    record OnboardingStart(StoreId store, String trackingRef, String returnUrl, String refreshUrl) {}

    /**
     * The hosted URL the UI opens, plus the tracking ref echoed back on
     * return/webhook. {@code accountId} is set when the provider mints the
     * connected account up front (Stripe creates the Account at start); null when
     * it only arrives on return (PayPal).
     */
    record OnboardingSession(String actionUrl, String trackingRef, String accountId) {}

    /** A return redirect's query params or a webhook body, tagged with the store/tracking ref. */
    record OnboardingCallback(StoreId store, String trackingRef, Map<String, Object> data) {}

    /** The provider's read on the account after a callback — persisted by the service. */
    record ReconcileResult(String accountId, MerchantAccountStatus status,
                           boolean chargesEnabled, Map<String, Object> detail) {}
}

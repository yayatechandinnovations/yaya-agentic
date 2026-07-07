package com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;

/**
 * Drives a PSP's hosted merchant-onboarding so a store owner can connect their
 * PayPal/Stripe account from inside the app — no API keys, no dashboards. Sibling
 * to {@code PaymentProvider} (a PSP can charge without self-serve onboarding, e.g.
 * dLocal, and vice-versa), same sandbox-first discipline. See
 * {@code merchant-psp-onboarding-design.md} §3.
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

    /** Begin onboarding for a tenant: returns a hosted URL to open + our tracking ref. */
    OnboardingSession start(OnboardingStart start);

    /** Reconcile a return redirect or webhook payload into a normalized account view. */
    ReconcileResult reconcile(OnboardingCallback callback);

    /** What the framework hands {@link #start}: the tenant + where the PSP returns the merchant. */
    record OnboardingStart(Ids.TenantId tenant, String trackingRef, String returnUrl, String refreshUrl) {}

    /** The hosted URL the UI opens, plus the tracking ref echoed back on return/webhook. */
    record OnboardingSession(String actionUrl, String trackingRef) {}

    /** A return redirect's query params or a webhook body, tagged with the tenant/tracking ref. */
    record OnboardingCallback(Ids.TenantId tenant, String trackingRef, Map<String, Object> data) {}

    /** The provider's read on the account after a callback — persisted by the service. */
    record ReconcileResult(String accountId, MerchantAccountStatus status,
                           boolean chargesEnabled, Map<String, Object> detail) {}
}

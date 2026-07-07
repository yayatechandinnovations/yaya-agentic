package com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.payment.StripeProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantOnboarding.OnboardingCallback;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantOnboarding.OnboardingStart;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantOnboarding.ReconcileResult;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stripe Connect onboarding, sandbox mode (no keys). Proves the connected
 * account is minted up front and that the account.updated status maps to the
 * right normalized state.
 */
class StripeMerchantOnboardingTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final StripeMerchantOnboarding onboarding = new StripeMerchantOnboarding(
            new StripeProperties(null, null, null, null), json);
    private static final Ids.TenantId T = new Ids.TenantId("t1");

    @Test
    void sandboxStartMintsAccountUpFront() {
        OnboardingSession s = onboarding.start(new OnboardingStart(
                T, "onb_s", "https://app.example/return", "https://app.example/refresh"));
        assertEquals("acct_sandbox_onb_s", s.accountId());
        assertTrue(s.actionUrl().startsWith("https://app.example/return"));
        assertTrue(s.actionUrl().contains("stripeAccount=acct_sandbox_onb_s"));
        assertTrue(s.actionUrl().contains("sandbox=1"));
    }

    @Test
    void sandboxReturnActivates() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_s",
                Map.of("sandbox", "1", "stripeAccount", "acct_sandbox_onb_s")));
        assertEquals(MerchantAccountStatus.ACTIVE, r.status());
        assertTrue(r.chargesEnabled());
        assertEquals("acct_sandbox_onb_s", r.accountId());
    }

    @Test
    void accountUpdatedWebhookWithChargesEnabledActivates() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_s",
                Map.of("id", "acct_123", "charges_enabled", true, "details_submitted", true)));
        assertEquals(MerchantAccountStatus.ACTIVE, r.status());
        assertTrue(r.chargesEnabled());
    }

    @Test
    void detailsSubmittedButNotChargeableIsRestricted() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_s",
                Map.of("id", "acct_123", "charges_enabled", false, "details_submitted", true)));
        assertEquals(MerchantAccountStatus.RESTRICTED, r.status());
        assertFalse(r.chargesEnabled());
    }

    @Test
    void bareReturnWithoutStatusStaysOnboarding() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_s",
                Map.of("accountId", "acct_123")));
        assertEquals(MerchantAccountStatus.ONBOARDING, r.status());
        assertFalse(r.chargesEnabled());
    }
}

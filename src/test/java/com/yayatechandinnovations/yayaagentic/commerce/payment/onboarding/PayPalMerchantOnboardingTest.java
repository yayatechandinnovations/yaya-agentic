package com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PayPalProperties;
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
 * The PayPal onboarding state machine, sandbox mode (no partner credentials).
 * Proves the merchant never leaves for a real PayPal page in a demo and that the
 * return/webhook payloads map to the right normalized status.
 */
class PayPalMerchantOnboardingTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PayPalMerchantOnboarding onboarding = new PayPalMerchantOnboarding(
            new PayPalProperties(null, null, null, null, null, null), json);
    private static final Ids.TenantId T = new Ids.TenantId("t1");

    @Test
    void sandboxStartPointsBackAtOurReturnEndpoint() {
        OnboardingSession s = onboarding.start(new OnboardingStart(
                T, "onb_abc", "https://app.example/return", "https://app.example/return"));
        assertEquals("onb_abc", s.trackingRef());
        assertTrue(s.actionUrl().startsWith("https://app.example/return"));
        assertTrue(s.actionUrl().contains("merchantIdInPayPal=SB-onb_abc"));
        assertTrue(s.actionUrl().contains("sandbox=1"));
    }

    @Test
    void sandboxReturnActivatesWithChargesEnabled() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_abc",
                Map.of("merchantIdInPayPal", "SB-onb_abc", "sandbox", "1")));
        assertEquals(MerchantAccountStatus.ACTIVE, r.status());
        assertTrue(r.chargesEnabled());
        assertEquals("SB-onb_abc", r.accountId());
    }

    @Test
    void liveReturnRedirectIsOnboardingUntilWebhookConfirms() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_x",
                Map.of("merchantIdInPayPal", "MERCH123")));
        assertEquals(MerchantAccountStatus.ONBOARDING, r.status());
        assertFalse(r.chargesEnabled());
        assertEquals("MERCH123", r.accountId());
    }

    @Test
    void onboardingCompletedWebhookActivates() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_x",
                Map.of("event_type", "MERCHANT.ONBOARDING.COMPLETED", "merchant_id", "MERCH123")));
        assertEquals(MerchantAccountStatus.ACTIVE, r.status());
        assertTrue(r.chargesEnabled());
    }

    @Test
    void consentRevokedWebhookDisables() {
        ReconcileResult r = onboarding.reconcile(new OnboardingCallback(T, "onb_x",
                Map.of("event_type", "MERCHANT.PARTNER-CONSENT-REVOKED", "merchant_id", "MERCH123")));
        assertEquals(MerchantAccountStatus.DISABLED, r.status());
        assertFalse(r.chargesEnabled());
    }
}

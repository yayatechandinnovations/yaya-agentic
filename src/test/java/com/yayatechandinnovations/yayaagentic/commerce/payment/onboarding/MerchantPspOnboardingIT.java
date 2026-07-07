package com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding;

import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end persistence + reconcile for in-app merchant PSP onboarding: begin →
 * ONBOARDING, return/webhook → ACTIVE, checkout resolves the connected account,
 * disconnect → stops routing. Proves a merchant "Connect PayPal" flow works with
 * no live credentials (sandbox), and that dLocal (no onboarding bean) is reported
 * unsupported. See {@code merchant-psp-onboarding-design.md}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class MerchantPspOnboardingIT {

    @Autowired MerchantPspAccountService service;
    @Autowired TenantRepository tenants;

    @Test
    void connectFlowActivatesAndResolvesConnectedAccount() {
        Ids.TenantId t = tenant("psp-onb-connect");

        OnboardingSession session = service.begin(t, "paypal",
                "https://app.example/return", "https://app.example/return");
        assertTrue(session.actionUrl().contains("merchantIdInPayPal=SB-"));
        assertEquals(MerchantAccountStatus.ONBOARDING, service.view(t, "paypal").status());
        assertTrue(service.resolveConnectedAccount(t, "paypal").isEmpty(),
                "not chargeable until the return/webhook confirms");

        // Sandbox return → ACTIVE, charges enabled.
        MerchantAccountView view = service.reconcileReturn(t, "paypal",
                Map.of("merchantIdInPayPal", "SB-" + session.trackingRef(), "sandbox", "1"));
        assertTrue(view.connected());
        assertEquals("SB-" + session.trackingRef(),
                service.resolveConnectedAccount(t, "paypal").orElseThrow());

        // Disconnect stops checkout from routing to it.
        assertEquals(MerchantAccountStatus.DISABLED, service.disconnect(t, "paypal").status());
        assertTrue(service.resolveConnectedAccount(t, "paypal").isEmpty());
    }

    @Test
    void asyncWebhookPromotesByTrackingRef() {
        Ids.TenantId t = tenant("psp-onb-webhook");
        OnboardingSession session = service.begin(t, "paypal",
                "https://app.example/return", "https://app.example/return");

        MerchantAccountView view = service.reconcileWebhook(session.trackingRef(),
                Map.of("event_type", "MERCHANT.ONBOARDING.COMPLETED", "merchant_id", "MERCH-1"))
                .orElseThrow();

        assertTrue(view.connected());
        assertEquals("MERCH-1", service.resolveConnectedAccount(t, "paypal").orElseThrow());
    }

    @Test
    void dlocalHasNoSelfServeOnboarding() {
        assertFalse(service.supports("dlocal"));
        assertFalse(service.view(tenant("psp-onb-dlocal"), "dlocal").onboardingSupported());
    }

    private Ids.TenantId tenant(String id) {
        if (!tenants.existsById(id)) tenants.save(new TenantEntity(id, id));
        return new Ids.TenantId(id);
    }
}

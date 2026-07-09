package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.payments.internal.PaymentProviderRouter;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccountStatus;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccountView;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §8.3/§10.1 — merchant PSP onboarding end to end in sandbox: Connect PayPal
 * with no partner credentials fabricates the hosted flow, the return reconciles
 * to ACTIVE + charges-enabled, and from then on the router charges that store
 * through PayPal — disconnecting falls back to the default provider. Also the
 * §10.3 fail-closed rule: an unconfigured PSP webhook answers 503 and
 * processes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PspOnboardingIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_sales");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.sales.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
        r.add("yaya.sales.inventory.sweep-enabled", () -> "false");
    }

    @Autowired MerchantAccounts accounts;
    @Autowired PaymentProviderRouter router;
    @Autowired StoreService storeService;
    @Autowired MockMvc mvc;

    StoreId store;

    @BeforeEach
    void newStore() {
        store = storeService.create(new TenantRef("tenant-psp"), "PSP Store", "DOP",
                StorePolicies.defaults()).id();
    }

    @Test
    void paypalSandboxOnboardingConnectsAndRoutesCheckoutToPaypal() {
        // Fresh store: nothing connected, router charges through the default (sandbox).
        assertThat(router.resolve(store).name()).isEqualTo("sandbox");
        assertThat(accounts.view(store, "paypal").status()).isEqualTo(MerchantAccountStatus.PENDING);

        // Begin: no partner credentials → fabricated action URL straight back at
        // the return endpoint, row moves to ONBOARDING.
        OnboardingSession session = accounts.begin(store, "paypal",
                "https://console.example/psp/return", "https://console.example/psp/return");
        assertThat(session.actionUrl())
                .contains("merchantIdInPayPal=SB-" + session.trackingRef())
                .contains("sandbox=1");
        assertThat(session.trackingRef()).startsWith("onb_");
        assertThat(accounts.view(store, "paypal").status()).isEqualTo(MerchantAccountStatus.ONBOARDING);

        // The console posts the redirect's query params back — reconcile → ACTIVE.
        MerchantAccountView view = accounts.reconcileReturn(store, "paypal",
                queryParams(session.actionUrl()));
        assertThat(view.status()).isEqualTo(MerchantAccountStatus.ACTIVE);
        assertThat(view.chargesEnabled()).isTrue();
        assertThat(view.connected()).isTrue();
        assertThat(view.accountId()).isEqualTo("SB-" + session.trackingRef());

        // Connecting PayPal IS choosing PayPal.
        assertThat(router.resolve(store).name()).isEqualTo("paypal");

        // Disconnect (soft) → DISABLED, routing falls back to the default.
        MerchantAccountView disconnected = accounts.disconnect(store, "paypal");
        assertThat(disconnected.status()).isEqualTo(MerchantAccountStatus.DISABLED);
        assertThat(disconnected.connected()).isFalse();
        assertThat(router.resolve(store).name()).isEqualTo("sandbox");
    }

    @Test
    void stripeSandboxOnboardingMintsTheAccountUpFrontAndActivatesOnReturn() {
        OnboardingSession session = accounts.begin(store, "stripe",
                "https://console.example/psp/return", "https://console.example/psp/return");
        assertThat(session.accountId()).startsWith("acct_sandbox_");
        assertThat(accounts.view(store, "stripe").accountId()).isEqualTo(session.accountId());

        MerchantAccountView view = accounts.reconcileReturn(store, "stripe",
                queryParams(session.actionUrl()));
        assertThat(view.connected()).isTrue();
        assertThat(accounts.connectedAccountId(store, "stripe")).contains(session.accountId());
        assertThat(router.resolve(store).name()).isEqualTo("stripe");
    }

    @Test
    void dlocalHasNoSelfServeOnboarding() {
        assertThat(accounts.supports("dlocal")).isFalse();
        assertThat(accounts.view(store, "dlocal").onboardingSupported()).isFalse();
    }

    @Test
    void unconfiguredPspWebhooksAnswer503AndProcessNothing() throws Exception {
        // No signing secret / credentials in this context — fail closed (§10.3),
        // never accept unsigned settle input.
        mvc.perform(post("/v1/sales/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/v1/sales/webhooks/paypal").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"WH-1\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/v1/sales/webhooks/dlocal").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ord-1\",\"status\":\"PAID\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    /** The fabricated action URL's query string, as the console would post it back. */
    private static Map<String, Object> queryParams(String url) {
        Map<String, Object> params = new HashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }
}

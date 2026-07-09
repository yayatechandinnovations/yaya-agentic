package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §10.3 — the Stripe webhook edge with a signing secret configured: the
 * {@code t=…,v1=…} HMAC is verified before anything is parsed (wrong or missing
 * signature → 403, stale timestamp → 403), a valid {@code account.updated}
 * reconciles the connected account, and a replayed event id acknowledges as a
 * duplicate without re-processing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PspWebhookIT {

    private static final String SIGNING_SECRET = "whsec_test_secret";

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
        r.add("yaya.sales.payments.stripe.webhook-signing-secret", () -> SIGNING_SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired MerchantAccounts accounts;
    @Autowired StoreService storeService;

    @Test
    void wrongSignatureIsRejectedBeforeAnyProcessing() throws Exception {
        String body = "{\"id\":\"evt_bad\",\"type\":\"account.updated\"}";
        long now = System.currentTimeMillis() / 1000L;
        mvc.perform(post("/v1/sales/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=" + now + ",v1=deadbeef")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        mvc.perform(post("/v1/sales/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_unsigned\",\"type\":\"account.updated\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleTimestampIsOutsideTheReplayWindow() throws Exception {
        String body = "{\"id\":\"evt_old\",\"type\":\"account.updated\"}";
        long stale = System.currentTimeMillis() / 1000L - 3600;
        mvc.perform(post("/v1/sales/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=" + stale + ",v1=" + sign(stale, body))
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void validAccountUpdatedReconcilesTheConnectedAccountAndReplaysAreDuplicates() throws Exception {
        StoreId store = storeService.create(new TenantRef("tenant-wh"), "Webhook Store", "DOP",
                StorePolicies.defaults()).id();
        // Stripe onboarding mints the acct_… up front — that is what account.updated
        // correlates by.
        OnboardingSession session = accounts.begin(store, "stripe",
                "https://console.example/psp/return", "https://console.example/psp/return");
        String acct = session.accountId();

        String body = "{\"id\":\"evt_ok\",\"type\":\"account.updated\",\"data\":{\"object\":"
                + "{\"id\":\"" + acct + "\",\"charges_enabled\":true,\"details_submitted\":true}}}";
        long now = System.currentTimeMillis() / 1000L;
        String header = "t=" + now + ",v1=" + sign(now, body);

        mvc.perform(post("/v1/sales/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        assertThat(accounts.view(store, "stripe").connected()).isTrue();

        // At-least-once delivery: the same event id acknowledges without re-processing.
        mvc.perform(post("/v1/sales/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    private static String sign(long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

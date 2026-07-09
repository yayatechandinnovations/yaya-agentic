package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.internal.providers.PaymentException;
import com.yayatechandinnovations.yayasales.payments.internal.providers.StripeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link MerchantOnboarding} for Stripe Connect (Express): create a connected
 * {@code Account}, then an {@code AccountLink} whose hosted pages collect the
 * merchant's details/KYC — no keys, no dashboard. The connected {@code acct_…} is
 * minted up front (returned on the {@link OnboardingSession}); charges become
 * enabled after the merchant finishes, confirmed by the {@code account.updated}
 * webhook. Design §8.3, §10.3.
 *
 * <p><b>Sandbox by default.</b> Without keys it fabricates an {@code acct_sandbox_…}
 * and an action URL that returns immediately, and {@link #reconcile} promotes to
 * ACTIVE — so Connect works with no Stripe account. No Stripe SDK: form-encoded
 * {@link RestClient} calls, identical to {@code StripeConnectPaymentProvider}.</p>
 */
@Component
public class StripeMerchantOnboarding implements MerchantOnboarding {

    private static final Logger log = LoggerFactory.getLogger(StripeMerchantOnboarding.class);

    private final StripeProperties props;
    private final ObjectMapper json;
    private final RestClient http = RestClient.create();

    public StripeMerchantOnboarding(StripeProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public OnboardingSession start(OnboardingStart start) {
        if (!props.isConfigured()) {
            String acct = "acct_sandbox_" + start.trackingRef();
            String sep = start.returnUrl().contains("?") ? "&" : "?";
            String actionUrl = start.returnUrl() + sep + "stripeAccount=" + acct + "&sandbox=1";
            log.info("Stripe sandbox onboarding {} (no keys configured)", start.trackingRef());
            return new OnboardingSession(actionUrl, start.trackingRef(), acct);
        }
        return live(start);
    }

    // ---- live Connect Account + AccountLink (verify with a test platform) --

    private OnboardingSession live(OnboardingStart start) {
        try {
            String accountId = post("/v1/accounts", Map.of("type", "express"))
                    .path("id").asText(null);
            if (accountId == null || accountId.isBlank()) {
                throw new PaymentException("Stripe returned no account id");
            }
            Map<String, String> linkBody = new LinkedHashMap<>();
            linkBody.put("account", accountId);
            linkBody.put("refresh_url", start.refreshUrl());
            linkBody.put("return_url", start.returnUrl());
            linkBody.put("type", "account_onboarding");
            String url = post("/v1/account_links", linkBody).path("url").asText(null);
            if (url == null || url.isBlank()) {
                throw new PaymentException("Stripe returned no AccountLink url");
            }
            return new OnboardingSession(url, start.trackingRef(), accountId);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("Stripe onboarding start failed for " + start.trackingRef(), e);
        }
    }

    @Override
    public ReconcileResult reconcile(OnboardingCallback callback) {
        Map<String, Object> data = callback.data();
        String accountId = str(data, "accountId", "stripeAccount", "id");
        boolean sandbox = "1".equals(str(data, "sandbox")) || Boolean.TRUE.equals(data.get("sandbox"));

        if (sandbox) {
            return new ReconcileResult(accountId, MerchantAccountStatus.ACTIVE, true, data);
        }

        Boolean chargesEnabled = bool(data.get("charges_enabled"));
        Boolean detailsSubmitted = bool(data.get("details_submitted"));
        // Return redirect carries no status — read it from Stripe.
        if (chargesEnabled == null && accountId != null && props.isConfigured()) {
            try {
                JsonNode acct = get("/v1/accounts/" + accountId);
                chargesEnabled = acct.path("charges_enabled").asBoolean(false);
                detailsSubmitted = acct.path("details_submitted").asBoolean(false);
            } catch (Exception e) {
                log.warn("Stripe account status fetch failed for {}", accountId, e);
            }
        }

        MerchantAccountStatus status;
        boolean enabled = Boolean.TRUE.equals(chargesEnabled);
        if (enabled) {
            status = MerchantAccountStatus.ACTIVE;
        } else if (Boolean.TRUE.equals(detailsSubmitted)) {
            status = MerchantAccountStatus.RESTRICTED;   // submitted but Stripe withholds charges
        } else {
            status = MerchantAccountStatus.ONBOARDING;
        }
        return new ReconcileResult(accountId, status, enabled, data);
    }

    // ---- helpers -----------------------------------------------------------

    private JsonNode post(String path, Map<String, String> form) throws Exception {
        String response = http.post()
                .uri(props.effectiveApiBase() + path)
                .header("Authorization", "Bearer " + props.secretKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(form(form))
                .retrieve()
                .body(String.class);
        return json.readTree(response == null ? "{}" : response);
    }

    private JsonNode get(String path) throws Exception {
        String response = http.get()
                .uri(props.effectiveApiBase() + path)
                .header("Authorization", "Bearer " + props.secretKey())
                .retrieve()
                .body(String.class);
        return json.readTree(response == null ? "{}" : response);
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Boolean bool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static String str(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }
}

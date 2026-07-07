package com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PayPalProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link MerchantOnboarding} for PayPal via the Partner Referrals API (the
 * "Connected Path"): the merchant clicks Connect, signs in / signs up on PayPal's
 * own pages, grants third-party permissions, and returns with a
 * {@code merchantIdInPayPal} — no keys, no dashboards. See
 * {@code merchant-psp-onboarding-design.md} §4.
 *
 * <p><b>Sandbox by default.</b> Without partner credentials it fabricates an
 * action URL that points straight back at our return endpoint with a demo
 * merchant id, and {@link #reconcile} promotes to {@code ACTIVE} immediately — so
 * the whole Connect → status-badge flow is demoable with no PayPal account.</p>
 */
@Component
public class PayPalMerchantOnboarding implements MerchantOnboarding {

    private static final Logger log = LoggerFactory.getLogger(PayPalMerchantOnboarding.class);

    private final PayPalProperties props;
    private final ObjectMapper json;
    private final RestClient http = RestClient.create();

    public PayPalMerchantOnboarding(PayPalProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public String provider() {
        return "paypal";
    }

    @Override
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public OnboardingSession start(OnboardingStart start) {
        if (!props.isConfigured()) {
            // Sandbox: the action URL leads straight back to our return endpoint
            // with a deterministic demo merchant id, so "Connect" completes offline.
            String sep = start.returnUrl().contains("?") ? "&" : "?";
            String actionUrl = start.returnUrl() + sep
                    + "merchantIdInPayPal=SB-" + start.trackingRef()
                    + "&permissionsGranted=true&sandbox=1";
            log.info("PayPal sandbox onboarding {} (no partner credentials configured)", start.trackingRef());
            return new OnboardingSession(actionUrl, start.trackingRef());
        }
        return live(start);
    }

    // ---- live Partner Referrals (verify against a real partner account) ----

    private OnboardingSession live(OnboardingStart start) {
        try {
            String token = oauthToken();
            String body = json.writeValueAsString(referralBody(start));
            String response = http.post()
                    .uri(props.effectiveApiBase() + "/v2/customer/partner-referrals")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = json.readTree(response == null ? "{}" : response);
            String actionUrl = linkHref(node, "action_url");
            if (actionUrl == null) {
                throw new PaymentException("PayPal Partner Referrals returned no action_url");
            }
            return new OnboardingSession(actionUrl, start.trackingRef());
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("PayPal onboarding start failed for " + start.trackingRef(), e);
        }
    }

    private Map<String, Object> referralBody(OnboardingStart start) {
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("return_url", start.returnUrl());
        override.put("return_url_description", "Return to the app after connecting PayPal.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tracking_id", start.trackingRef());          // echoed back on return + webhook
        body.put("operations", List.of(Map.of(
                "operation", "API_INTEGRATION",
                "api_integration_preference", Map.of("rest_api_integration", Map.of(
                        "integration_method", "PAYPAL",
                        "integration_type", "THIRD_PARTY",
                        "third_party_details", Map.of("features",
                                List.of("PAYMENT", "REFUND")))))));
        body.put("products", List.of("EXPRESS_CHECKOUT"));
        body.put("legal_consents", List.of(Map.of(
                "type", "SHARE_DATA_CONSENT", "granted", true)));
        body.put("partner_config_override", override);
        return body;
    }

    @Override
    public ReconcileResult reconcile(OnboardingCallback callback) {
        Map<String, Object> data = callback.data();
        String accountId = firstNonBlank(
                str(data, "merchantIdInPayPal"), str(data, "merchant_id"), str(data, "accountId"));
        String eventType = str(data, "event_type");
        boolean sandbox = "1".equals(str(data, "sandbox")) || Boolean.TRUE.equals(data.get("sandbox"));

        MerchantAccountStatus status;
        boolean chargesEnabled;
        if (sandbox) {
            status = MerchantAccountStatus.ACTIVE;
            chargesEnabled = true;
        } else if (eventType != null && eventType.toUpperCase(Locale.ROOT).contains("CONSENT-REVOKED")) {
            status = MerchantAccountStatus.DISABLED;
            chargesEnabled = false;
        } else if (eventType != null && eventType.toUpperCase(Locale.ROOT).contains("ONBOARDING.COMPLETED")) {
            status = MerchantAccountStatus.ACTIVE;
            chargesEnabled = true;
        } else if (accountId != null) {
            // Return redirect landed but not webhook-confirmed yet — connected, not chargeable.
            status = MerchantAccountStatus.ONBOARDING;
            chargesEnabled = false;
        } else {
            status = MerchantAccountStatus.PENDING;
            chargesEnabled = false;
        }
        return new ReconcileResult(accountId, status, chargesEnabled, data);
    }

    // ---- helpers -----------------------------------------------------------

    private String oauthToken() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        String tokenResponse = http.post()
                .uri(props.effectiveApiBase() + "/v1/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(String.class);
        String token = json.readTree(tokenResponse == null ? "{}" : tokenResponse)
                .path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new PaymentException("PayPal OAuth returned no access_token");
        }
        return token;
    }

    private static String linkHref(JsonNode node, String rel) {
        JsonNode links = node.path("links");
        if (links.isArray()) {
            for (JsonNode link : links) {
                if (rel.equalsIgnoreCase(link.path("rel").asText())) {
                    String href = link.path("href").asText(null);
                    if (href != null && !href.isBlank()) return href;
                }
            }
        }
        return null;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}

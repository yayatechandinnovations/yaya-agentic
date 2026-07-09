package com.yayatechandinnovations.yayasales.payments.internal.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link PaymentProvider} for PayPal (Orders v2 / Commerce Platform). PayPal
 * returns an approval URL the shopper opens to pay — which maps cleanly onto the
 * existing {@link AuthorizeOutcome.HostedUrl}, so the client checkout handler is
 * unchanged and no sealed variant is added. Covers the Dominican Republic and
 * most markets. Design §8.3.
 *
 * <p><b>Sandbox by default.</b> Without credentials it fabricates a deterministic
 * approval URL so the hosted-URL flow is demoable/testable with no external
 * dependency. With credentials it mints an OAuth token and creates a real order,
 * stamping our order id as the purchase unit's {@code custom_id} so the capture
 * webhook can echo it back. yaya-sales never sees a card either way.</p>
 */
@Component
public class PayPalPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PayPalPaymentProvider.class);

    private final PayPalProperties props;
    private final MerchantAccounts merchantAccounts;
    private final ObjectMapper json;
    private final RestClient http;

    public PayPalPaymentProvider(PayPalProperties props, MerchantAccounts merchantAccounts,
                                 ObjectMapper json) {
        this.props = props;
        this.merchantAccounts = merchantAccounts;
        this.json = json;
        this.http = RestClient.create();
    }

    /** Live requires PayPal credentials — lets the router tell a wired PayPal
     *  from the sandbox fallback. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public String name() {
        return "paypal";
    }

    @Override
    public AuthorizeOutcome authorize(AuthorizeRequest request) {
        long amountMinor = request.amount().amountMinor();
        String currency = request.amount().currency();
        String orderRef = request.order().value();

        if (!props.isConfigured()) {
            String url = props.effectiveSandboxUrl() + "?token=" + orderRef
                    + "&amount=" + amountMinor + "&currency=" + currency;
            log.info("PayPal sandbox checkout {} for {} {} (no credentials configured)",
                    orderRef, amountMinor, currency);
            return new AuthorizeOutcome.HostedUrl(url, orderRef);
        }
        return live(request, orderRef, amountMinor, currency);
    }

    @Override
    public RefundOutcome refund(RefundRequest request) {
        if (!props.isConfigured()) {
            return new RefundOutcome.Refunded("re_sandbox_" + request.pspRef());
        }
        // The legacy core never wired a PayPal refund API call and we don't invent
        // one unverified — live refunds settle in the PSP dashboard for now.
        return new RefundOutcome.Failed(
                "refunds for paypal are settled out-of-band in v1 — process in the PSP dashboard");
    }

    // ---- live PayPal Orders v2 (verify against a real sandbox account) -----

    private AuthorizeOutcome live(AuthorizeRequest request, String orderRef,
                                  long amountMinor, String currency) {
        try {
            String accessToken = oauthToken();
            String body = json.writeValueAsString(orderBody(request, orderRef, amountMinor, currency));

            RestClient.RequestBodySpec spec = http.post()
                    .uri(props.effectiveApiBase() + "/v2/checkout/orders")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON);
            if (props.partnerAttributionId() != null && !props.partnerAttributionId().isBlank()) {
                spec = spec.header("PayPal-Partner-Attribution-Id", props.partnerAttributionId());
            }
            String response = spec.body(body).retrieve().body(String.class);

            JsonNode node = json.readTree(response == null ? "{}" : response);
            String approve = approvalUrl(node);
            if (approve == null) {
                throw new PaymentException("PayPal returned no approval link for order " + orderRef);
            }
            String paypalOrderId = node.path("id").asText(null);
            return new AuthorizeOutcome.HostedUrl(approve,
                    paypalOrderId == null || paypalOrderId.isBlank() ? orderRef : paypalOrderId);
        } catch (Exception e) {
            log.warn("PayPal checkout failed for order {}", orderRef, e);
            return new AuthorizeOutcome.Failed("PayPal checkout failed for order " + orderRef);
        }
    }

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
        JsonNode node = json.readTree(tokenResponse == null ? "{}" : tokenResponse);
        String token = node.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new PaymentException("PayPal OAuth returned no access_token");
        }
        return token;
    }

    private Map<String, Object> orderBody(AuthorizeRequest request, String orderRef,
                                          long amountMinor, String currency) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("currency_code", currency);
        // PayPal takes a decimal amount; we assume 2-decimal (exponent-2)
        // currencies — zero-decimal currencies would need the ISO-4217 exponent
        // table before going live there.
        amount.put("value", new BigDecimal(amountMinor).movePointLeft(2)
                .setScale(2, RoundingMode.HALF_UP).toPlainString());

        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("reference_id", orderRef);
        unit.put("custom_id", orderRef);            // echoed back on the capture webhook
        unit.put("amount", amount);
        merchantAccounts.connectedAccountId(request.store(), name())
                .ifPresent(id -> unit.put("payee", Map.of("merchant_id", id)));  // multiparty merchant

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "CAPTURE");
        body.put("purchase_units", java.util.List.of(unit));
        return body;
    }

    private static String approvalUrl(JsonNode order) {
        JsonNode links = order.path("links");
        if (links.isArray()) {
            for (JsonNode link : links) {
                if ("approve".equalsIgnoreCase(link.path("rel").asText())) {
                    String href = link.path("href").asText(null);
                    if (href != null && !href.isBlank()) return href;
                }
            }
        }
        return null;
    }
}

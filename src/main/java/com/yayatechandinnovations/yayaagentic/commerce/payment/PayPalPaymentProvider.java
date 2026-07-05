package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * existing {@link CheckoutOutcome.HostedUrl}, so the client checkout handler is
 * unchanged and no sealed variant is added. Covers the Dominican Republic and
 * most markets. See {@code commerce-redesign-authoring-rendering-paypal.md} Pillar C.
 *
 * <p><b>Sandbox by default.</b> Without credentials it fabricates a deterministic
 * approval URL so the hosted-URL flow is demoable/testable with no external
 * dependency. With credentials it mints an OAuth token and creates a real order,
 * stamping our {@code orderRef} as the purchase unit's {@code custom_id} so the
 * capture webhook can echo it back (Pillar C §C.1). The framework never sees a
 * card either way.</p>
 */
@Component
public class PayPalPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PayPalPaymentProvider.class);

    private final PayPalProperties props;
    private final ObjectMapper json;
    private final RestClient http;

    public PayPalPaymentProvider(PayPalProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // Blocking client for the live PSP call; runs off the event loop.
        this.http = RestClient.create();
    }

    /** Live requires PayPal credentials — lets the router tell a wired PayPal from the sandbox fallback. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public String name() {
        return "paypal";
    }

    @Override
    public CheckoutOutcome createCheckout(CheckoutRequest request) {
        long amountMinor = request.cart().subtotal().amountMinor();
        String currency = request.currency();
        String orderRef = orderRef(request, amountMinor);

        if (!props.isConfigured()) {
            String url = props.effectiveSandboxUrl() + "?token=" + orderRef
                    + "&amount=" + amountMinor + "&currency=" + currency;
            log.info("PayPal sandbox checkout {} for {} {} (no credentials configured)",
                    orderRef, amountMinor, currency);
            return new CheckoutOutcome.HostedUrl(orderRef, amountMinor, currency, url);
        }
        return live(request, orderRef, amountMinor, currency);
    }

    // ---- live PayPal Orders v2 (verify against a real sandbox account) -----

    private CheckoutOutcome live(CheckoutRequest request, String orderRef, long amountMinor, String currency) {
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
            return new CheckoutOutcome.HostedUrl(orderRef, amountMinor, currency, approve);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("PayPal checkout failed for order " + orderRef, e);
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

    private Map<String, Object> orderBody(CheckoutRequest request, String orderRef,
                                          long amountMinor, String currency) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("currency_code", currency);
        amount.put("value", new BigDecimal(amountMinor).movePointLeft(2)
                .setScale(2, RoundingMode.HALF_UP).toPlainString());

        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("reference_id", orderRef);
        unit.put("custom_id", orderRef);            // echoed back on the capture webhook
        unit.put("amount", amount);
        if (request.connectedAccountId() != null && !request.connectedAccountId().isBlank()) {
            unit.put("payee", Map.of("merchant_id", request.connectedAccountId()));  // multiparty merchant
        }

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

    /** Deterministic per (session, amount) so a retry of the same cart is idempotent-ish. */
    private static String orderRef(CheckoutRequest request, long amountMinor) {
        String s = request.session() == null ? "anon" : request.session().value();
        String shortId = s.length() > 8 ? s.substring(0, 8) : s;
        return "ppl_" + shortId + "_" + amountMinor;
    }
}

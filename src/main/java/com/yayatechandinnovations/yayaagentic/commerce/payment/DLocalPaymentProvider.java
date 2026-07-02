package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default {@link PaymentProvider} for the Dominican Republic / LatAm. dLocal
 * for Platforms is the Connect-equivalent local merchant-of-record, so the
 * framework stays out of DR regulatory scope (§11, open Q#7).
 *
 * <p><b>Sandbox by default.</b> Without credentials ({@link DLocalProperties#isConfigured()}
 * false) it fabricates a deterministic hosted-checkout URL so the storefront is
 * demoable and testable with no external dependency. With credentials it opens a
 * REDIRECT payment on dLocal's API, signing per the documented {@code V2-HMAC-SHA256}
 * scheme, and returns the {@code redirect_url}. The framework never sees a card
 * either way.</p>
 *
 * <p>The live path is the seam that needs verification against a real dLocal
 * sandbox account before production (exact payer/country fields, amount exponent).</p>
 */
@Component
public class DLocalPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(DLocalPaymentProvider.class);

    private final DLocalProperties props;
    private final ObjectMapper json;
    private final RestClient http;

    public DLocalPaymentProvider(DLocalProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // Blocking client for the live PSP call; this WebFlux app doesn't expose a
        // RestClient.Builder bean, and the call only runs off the event loop.
        this.http = RestClient.create();
    }

    /** Live requires all dLocal credentials — surfaced so the router can tell a
     *  wired dLocal from the sandbox fallback. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public String name() {
        return "dlocal";
    }

    @Override
    public CheckoutOutcome createCheckout(CheckoutRequest request) {
        long amountMinor = request.cart().subtotal().amountMinor();
        String currency = request.currency();
        String orderRef = orderRef(request, amountMinor);

        if (!props.isConfigured()) {
            String url = props.effectiveSandboxUrl() + "/" + orderRef
                    + "?amount=" + amountMinor + "&currency=" + currency;
            log.info("dLocal sandbox checkout {} for {} {} (no credentials configured)",
                    orderRef, amountMinor, currency);
            return new CheckoutOutcome.HostedUrl(orderRef, amountMinor, currency, url);
        }
        return live(request, orderRef, amountMinor, currency);
    }

    // ---- live dLocal API (verify against a real sandbox account) ----------

    private CheckoutOutcome live(CheckoutRequest request, String orderRef, long amountMinor, String currency) {
        try {
            String body = json.writeValueAsString(paymentBody(request, orderRef, amountMinor, currency));
            String date = Instant.now().toString();
            String signature = DLocalSignatures.hmacHex(props.xLogin() + date + body, props.secretKey());

            String response = http.post()
                    .uri(props.effectiveApiBase() + "/secure_payments")
                    .header("X-Date", date)
                    .header("X-Login", props.xLogin())
                    .header("X-Trans-Key", props.transKey())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "V2-HMAC-SHA256, Signature: " + signature)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node = json.readTree(response == null ? "{}" : response);
            String redirect = node.path("redirect_url").asText(null);
            if (redirect == null || redirect.isBlank()) {
                throw new PaymentException("dLocal returned no redirect_url for order " + orderRef);
            }
            return new CheckoutOutcome.HostedUrl(orderRef, amountMinor, currency, redirect);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("dLocal checkout failed for order " + orderRef, e);
        }
    }

    private Map<String, Object> paymentBody(CheckoutRequest request, String orderRef,
                                            long amountMinor, String currency) {
        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("name", request.principalSubject() == null ? "Guest" : request.principalSubject());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", new BigDecimal(amountMinor).movePointLeft(2).setScale(2, RoundingMode.HALF_UP));
        body.put("currency", currency);
        body.put("order_id", orderRef);
        body.put("payment_method_flow", "REDIRECT");
        body.put("payer", payer);
        if (request.connectedAccountId() != null) {
            body.put("merchant_id", request.connectedAccountId());  // platform sub-account
        }
        return body;
    }

    /** Deterministic per (session, amount) so a retry of the same cart is idempotent-ish. */
    private static String orderRef(CheckoutRequest request, long amountMinor) {
        String s = request.session() == null ? "anon" : request.session().value();
        String shortId = s.length() > 8 ? s.substring(0, 8) : s;
        return "ord_" + shortId + "_" + amountMinor;
    }
}

package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stripe (Connect) {@link PaymentProvider} — the {@code payment_intent} path. It
 * creates a PaymentIntent on the merchant's connected account and hands the
 * client a {@code clientSecret} + {@code publishableKey} to mount the Stripe
 * drop-in; card data never touches yaya-agentic (§11).
 *
 * <p><b>Sandbox by default.</b> Without keys ({@link StripeProperties#isConfigured()}
 * false) it fabricates a deterministic {@link CheckoutOutcome.PaymentIntent} so the
 * whole {@code checkout → confirm → checkout_result(payment_intent) → client mounts
 * drop-in} flow is exercisable with no external dependency. The fabricated secret
 * won't confirm against real Stripe (the client's sheet fails gracefully) — it
 * proves the wiring. With test keys it calls Stripe's live API and returns a real
 * {@code client_secret} a test card can confirm.</p>
 *
 * <p>Deliberately no Stripe Java SDK — a form-encoded {@link RestClient} call keeps
 * the dependency surface identical to {@link DLocalPaymentProvider}.</p>
 */
@Component
public class StripeConnectPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectPaymentProvider.class);

    private final StripeProperties props;
    private final ObjectMapper json;
    private final RestClient http;

    public StripeConnectPaymentProvider(StripeProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // Blocking client; the call only runs off the WebFlux event loop.
        this.http = RestClient.create();
    }

    /** Live requires both keys — surfaced so the router can auto-prefer a wired Stripe. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public CheckoutOutcome createCheckout(CheckoutRequest request) {
        long amountMinor = request.cart().subtotal().amountMinor();
        String currency = request.currency();
        String orderRef = orderRef(request, amountMinor);

        if (!props.isConfigured()) {
            log.info("Stripe sandbox PaymentIntent {} for {} {} (no keys configured)",
                    orderRef, amountMinor, currency);
            return new CheckoutOutcome.PaymentIntent(orderRef, amountMinor, currency,
                    "pi_sandbox_" + orderRef + "_secret_" + orderRef,
                    props.effectivePublishableKey());
        }
        return live(request, orderRef, amountMinor, currency);
    }

    // ---- live Stripe API (test keys confirm with a test card) --------------

    private CheckoutOutcome live(CheckoutRequest request, String orderRef, long amountMinor, String currency) {
        try {
            String body = form(paymentIntentBody(orderRef, amountMinor, currency));

            RestClient.RequestBodySpec spec = http.post()
                    .uri(props.effectiveApiBase() + "/v1/payment_intents")
                    .header("Authorization", "Bearer " + props.secretKey())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    // Idempotency: the same cart retried yields the same intent, not a duplicate.
                    .header("Idempotency-Key", orderRef);

            // Connect: charge directly ON the merchant's connected account (Express /
            // Standard = merchant is MoR). Absent = a platform charge.
            if (request.connectedAccountId() != null && !request.connectedAccountId().isBlank()) {
                spec = spec.header("Stripe-Account", request.connectedAccountId());
            }

            String response = spec.body(body).retrieve().body(String.class);

            JsonNode node = json.readTree(response == null ? "{}" : response);
            String clientSecret = node.path("client_secret").asText(null);
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new PaymentException("Stripe returned no client_secret for order " + orderRef);
            }
            return new CheckoutOutcome.PaymentIntent(orderRef, amountMinor, currency,
                    clientSecret, props.publishableKey());
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("Stripe checkout failed for order " + orderRef, e);
        }
    }

    /** Stripe amounts are already in the smallest currency unit — our {@code amountMinor}
     *  maps directly. Currency must be lower-case ISO-4217. */
    private static Map<String, String> paymentIntentBody(String orderRef, long amountMinor, String currency) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("amount", Long.toString(amountMinor));
        body.put("currency", currency.toLowerCase(Locale.ROOT));
        body.put("automatic_payment_methods[enabled]", "true");
        body.put("metadata[order_id]", orderRef);
        return body;
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

    /** Deterministic per (session, amount) so a retry of the same cart is idempotent-ish
     *  (matches {@link DLocalPaymentProvider} so order refs read the same across PSPs). */
    private static String orderRef(CheckoutRequest request, long amountMinor) {
        String s = request.session() == null ? "anon" : request.session().value();
        String shortId = s.length() > 8 ? s.substring(0, 8) : s;
        return "ord_" + shortId + "_" + amountMinor;
    }
}

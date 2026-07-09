package com.yayatechandinnovations.yayasales.payments.internal.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stripe (Connect) {@link PaymentProvider} — the {@code payment_intent} path. It
 * creates a PaymentIntent on the merchant's connected account and hands the
 * client a {@code clientSecret} + {@code publishableKey} to mount the Stripe
 * drop-in; card data never touches yaya-sales (design §8.3, §15).
 *
 * <p><b>Sandbox by default.</b> Without keys ({@link StripeProperties#isConfigured()}
 * false) it fabricates a deterministic {@link AuthorizeOutcome.Intent} so the
 * whole {@code checkout → payment_intent → client mounts drop-in} flow is
 * exercisable with no external dependency. The fabricated secret won't confirm
 * against real Stripe (the client's sheet fails gracefully) — it proves the
 * wiring. With test keys it calls Stripe's live API and returns a real
 * {@code client_secret} a test card can confirm.</p>
 *
 * <p>Deliberately no Stripe Java SDK — a form-encoded {@link RestClient} call keeps
 * the dependency surface identical to {@link DLocalPaymentProvider}.</p>
 */
@Component
public class StripeConnectPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectPaymentProvider.class);

    private final StripeProperties props;
    private final MerchantAccounts merchantAccounts;
    private final ObjectMapper json;
    private final RestClient http;

    public StripeConnectPaymentProvider(StripeProperties props, MerchantAccounts merchantAccounts,
                                        ObjectMapper json) {
        this.props = props;
        this.merchantAccounts = merchantAccounts;
        this.json = json;
        this.http = RestClient.create();
    }

    /** Live requires both keys — surfaced so the router can tell a wired Stripe
     *  from the sandbox fallback. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public AuthorizeOutcome authorize(AuthorizeRequest request) {
        long amountMinor = request.amount().amountMinor();
        String currency = request.amount().currency();
        String orderRef = request.order().value();

        if (!props.isConfigured()) {
            log.info("Stripe sandbox PaymentIntent {} for {} {} (no keys configured)",
                    orderRef, amountMinor, currency);
            return new AuthorizeOutcome.Intent(
                    "pi_sandbox_" + orderRef + "_secret_" + orderRef,
                    props.effectivePublishableKey(),
                    "pi_sandbox_" + orderRef);
        }
        return live(request, orderRef, amountMinor, currency);
    }

    @Override
    public RefundOutcome refund(RefundRequest request) {
        if (!props.isConfigured()) {
            return new RefundOutcome.Refunded("re_sandbox_" + request.pspRef());
        }
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("payment_intent", request.pspRef());
            body.put("amount", Long.toString(request.amount().amountMinor()));

            RestClient.RequestBodySpec spec = http.post()
                    .uri(props.effectiveApiBase() + "/v1/refunds")
                    .header("Authorization", "Bearer " + props.secretKey())
                    .header("Content-Type", "application/x-www-form-urlencoded");
            spec = withConnectedAccount(spec, request.store());

            String response = spec.body(form(body)).retrieve().body(String.class);
            JsonNode node = json.readTree(response == null ? "{}" : response);
            String refundId = node.path("id").asText(null);
            if (refundId == null || refundId.isBlank()) {
                return new RefundOutcome.Failed("Stripe returned no refund id for " + request.pspRef());
            }
            return new RefundOutcome.Refunded(refundId);
        } catch (Exception e) {
            log.warn("Stripe refund failed for {}", request.pspRef(), e);
            return new RefundOutcome.Failed("Stripe refund failed for " + request.pspRef());
        }
    }

    // ---- live Stripe API (test keys confirm with a test card) --------------

    private AuthorizeOutcome live(AuthorizeRequest request, String orderRef,
                                  long amountMinor, String currency) {
        try {
            String body = form(paymentIntentBody(orderRef, amountMinor, currency));

            RestClient.RequestBodySpec spec = http.post()
                    .uri(props.effectiveApiBase() + "/v1/payment_intents")
                    .header("Authorization", "Bearer " + props.secretKey())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    // Idempotency: the same order retried yields the same intent, not a duplicate.
                    .header("Idempotency-Key", orderRef);

            // Connect: charge directly ON the merchant's connected account (Express /
            // Standard = merchant is MoR). Absent = a platform charge.
            spec = withConnectedAccount(spec, request.store());

            String response = spec.body(body).retrieve().body(String.class);

            JsonNode node = json.readTree(response == null ? "{}" : response);
            String clientSecret = node.path("client_secret").asText(null);
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new PaymentException("Stripe returned no client_secret for order " + orderRef);
            }
            String intentId = node.path("id").asText(null);
            return new AuthorizeOutcome.Intent(clientSecret, props.publishableKey(),
                    intentId == null || intentId.isBlank() ? orderRef : intentId);
        } catch (Exception e) {
            log.warn("Stripe checkout failed for order {}", orderRef, e);
            return new AuthorizeOutcome.Failed("Stripe checkout failed for order " + orderRef);
        }
    }

    private RestClient.RequestBodySpec withConnectedAccount(RestClient.RequestBodySpec spec,
                                                            com.yayatechandinnovations.yayasales.platform.Ids.StoreId store) {
        Optional<String> connected = merchantAccounts.connectedAccountId(store, name());
        return connected.isPresent() ? spec.header("Stripe-Account", connected.get()) : spec;
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
}

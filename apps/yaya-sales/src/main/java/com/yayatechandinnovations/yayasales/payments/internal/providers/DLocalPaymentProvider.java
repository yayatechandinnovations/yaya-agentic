package com.yayatechandinnovations.yayasales.payments.internal.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
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
 * The {@link PaymentProvider} for the Dominican Republic / LatAm. dLocal for
 * Platforms is the Connect-equivalent local merchant-of-record, so yaya-sales
 * stays out of DR regulatory scope (design §8.3, §15).
 *
 * <p><b>Sandbox by default.</b> Without credentials ({@link DLocalProperties#isConfigured()}
 * false) it fabricates a deterministic hosted-checkout URL so the storefront is
 * demoable and testable with no external dependency. With credentials it opens a
 * REDIRECT payment on dLocal's API, signing per the documented {@code V2-HMAC-SHA256}
 * scheme, and returns the {@code redirect_url}. yaya-sales never sees a card
 * either way.</p>
 *
 * <p>The live path is the seam that needs verification against a real dLocal
 * sandbox account before production (exact payer/country fields, amount exponent).</p>
 */
@Component
public class DLocalPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(DLocalPaymentProvider.class);

    private final DLocalProperties props;
    private final MerchantAccounts merchantAccounts;
    private final ObjectMapper json;
    private final RestClient http;

    public DLocalPaymentProvider(DLocalProperties props, MerchantAccounts merchantAccounts,
                                 ObjectMapper json) {
        this.props = props;
        this.merchantAccounts = merchantAccounts;
        this.json = json;
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
    public AuthorizeOutcome authorize(AuthorizeRequest request) {
        long amountMinor = request.amount().amountMinor();
        String currency = request.amount().currency();
        // yaya-sales has real order aggregates — the order id IS the PSP order
        // reference, so the webhook settles by order id directly.
        String orderRef = request.order().value();

        if (!props.isConfigured()) {
            String url = props.effectiveSandboxUrl() + "/" + orderRef
                    + "?amount=" + amountMinor + "&currency=" + currency;
            log.info("dLocal sandbox checkout {} for {} {} (no credentials configured)",
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
        // The legacy core never wired a dLocal refund API call and we don't invent
        // one unverified — live refunds settle in the PSP dashboard for now.
        return new RefundOutcome.Failed(
                "refunds for dlocal are settled out-of-band in v1 — process in the PSP dashboard");
    }

    // ---- live dLocal API (verify against a real sandbox account) ----------

    private AuthorizeOutcome live(AuthorizeRequest request, String orderRef,
                                  long amountMinor, String currency) {
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
            String pspRef = node.path("id").asText(null);
            return new AuthorizeOutcome.HostedUrl(redirect,
                    pspRef == null || pspRef.isBlank() ? orderRef : pspRef);
        } catch (Exception e) {
            log.warn("dLocal checkout failed for order {}", orderRef, e);
            return new AuthorizeOutcome.Failed("dLocal checkout failed for order " + orderRef);
        }
    }

    private Map<String, Object> paymentBody(AuthorizeRequest request, String orderRef,
                                            long amountMinor, String currency) {
        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("name", "Customer");
        Map<String, Object> body = new LinkedHashMap<>();
        // dLocal takes a decimal amount; we assume 2-decimal (exponent-2)
        // currencies, which covers DOP/USD/EUR — zero-decimal currencies would
        // need the ISO-4217 exponent table before going live there.
        body.put("amount", new BigDecimal(amountMinor).movePointLeft(2).setScale(2, RoundingMode.HALF_UP));
        body.put("currency", currency);
        body.put("order_id", orderRef);
        body.put("payment_method_flow", "REDIRECT");
        body.put("payer", payer);
        merchantAccounts.connectedAccountId(request.store(), name())
                .ifPresent(id -> body.put("merchant_id", id));   // platform sub-account
        return body;
    }
}

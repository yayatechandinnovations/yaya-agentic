package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.payment.StripeProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantPspAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Public edge for Stripe Connect onboarding notifications. On {@code
 * account.updated} it reconciles the merchant's connected-account status
 * ({@code charges_enabled}/{@code details_submitted}) by account id. Verifies the
 * {@code Stripe-Signature} HMAC when a signing secret is configured; sandbox/dev
 * accepts unsigned (mirrors {@link PayPalWebhookController}). Charge/refund
 * webhooks are out of scope here — those flow through the checkout PSP path.
 * See {@code merchant-psp-onboarding-design.md} §7, §10.
 */
@RestController
@RequestMapping("/v1/commerce/webhooks")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    private static final long TOLERANCE_SECONDS = 300;

    private final StripeProperties props;
    private final MerchantPspAccountService merchantAccounts;
    private final ObjectMapper json;

    public StripeWebhookController(StripeProperties props, MerchantPspAccountService merchantAccounts,
                                   ObjectMapper json) {
        this.props = props;
        this.merchantAccounts = merchantAccounts;
        this.json = json;
    }

    @PostMapping(value = "/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> stripe(@RequestBody String rawBody, ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        return Mono.fromCallable(() -> handle(rawBody, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Void> handle(String rawBody, HttpHeaders headers) {
        if (!verified(rawBody, headers.getFirst("Stripe-Signature"))) {
            log.warn("Stripe webhook signature rejected");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String type;
        JsonNode object;
        try {
            JsonNode node = json.readTree(rawBody == null ? "{}" : rawBody);
            type = node.path("type").asText(null);
            object = node.path("data").path("object");
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (!"account.updated".equals(type)) {
            return ResponseEntity.ok().build();   // ack unrelated events; nothing to do
        }
        String accountId = object.path("id").asText(null);
        if (accountId == null || accountId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("charges_enabled", object.path("charges_enabled").asBoolean(false));
        payload.put("details_submitted", object.path("details_submitted").asBoolean(false));
        merchantAccounts.reconcileWebhookByAccount("stripe", accountId, payload);
        return ResponseEntity.ok().build();
    }

    /** Verify Stripe's {@code t=…,v1=…} HMAC-SHA256 over {@code t.payload}; accept
     *  unsigned only when no signing secret is configured (sandbox/dev). */
    private boolean verified(String rawBody, String signatureHeader) {
        String secret = props.webhookSigningSecret();
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || rawBody == null) {
            return false;
        }
        try {
            String timestamp = null;
            String v1 = null;
            for (String part : signatureHeader.split(",")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if ("t".equals(k)) timestamp = v;
                else if ("v1".equals(k) && v1 == null) v1 = v;
            }
            if (timestamp == null || v1 == null) return false;
            if (Math.abs(nowEpochSeconds() - Long.parseLong(timestamp)) > TOLERANCE_SECONDS) {
                return false;   // replay window
            }
            String expected = hmacSha256Hex(secret, timestamp + "." + rawBody);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Stripe webhook verification failed", e);
            return false;
        }
    }

    // Extracted so tests aren't clock-dependent is unnecessary here — Stripe stamps t.
    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

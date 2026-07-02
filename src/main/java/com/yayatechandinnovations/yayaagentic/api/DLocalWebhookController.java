package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.payment.DLocalProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.DLocalSignatures;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;

/**
 * Public-internet edge for dLocal payment notifications. Verifies the signature
 * first (when credentials are configured; sandbox accepts unsigned for local
 * dev), maps the dLocal status to our normalized status, and hands off to
 * {@link PaymentStatusService} to flip the order and clear the cart. Always acks
 * 200 for a well-formed, verified callback so dLocal stops retrying. See
 * {@code ecommerce-chat-design.md} §11.
 */
@RestController
@RequestMapping("/v1/commerce/webhooks")
public class DLocalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DLocalWebhookController.class);

    private final DLocalProperties props;
    private final PaymentStatusService paymentStatus;
    private final ObjectMapper json;

    public DLocalWebhookController(DLocalProperties props, PaymentStatusService paymentStatus, ObjectMapper json) {
        this.props = props;
        this.paymentStatus = paymentStatus;
        this.json = json;
    }

    @PostMapping(value = "/dlocal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> dlocal(@RequestBody String rawBody,
                                             @RequestHeader(value = "X-Date", required = false) String xDate,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Mono.fromCallable(() -> handle(rawBody, xDate, authorization))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Void> handle(String rawBody, String xDate, String authorization) {
        if (!verified(rawBody, xDate, authorization)) {
            log.warn("dLocal webhook signature rejected");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String orderRef;
        String providerStatus;
        try {
            JsonNode node = json.readTree(rawBody == null ? "{}" : rawBody);
            orderRef = node.path("order_id").asText(null);
            providerStatus = node.path("status").asText(null);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (orderRef == null || orderRef.isBlank() || providerStatus == null) {
            return ResponseEntity.badRequest().build();
        }
        paymentStatus.applyStatus(orderRef, normalize(providerStatus));
        return ResponseEntity.ok().build();   // ack even for unknown order — idempotent, stops retries
    }

    /** Sandbox (unconfigured) accepts; live verifies the V2-HMAC-SHA256 signature over the raw body. */
    private boolean verified(String rawBody, String xDate, String authorization) {
        if (!props.isConfigured()) return true;
        if (authorization == null || xDate == null) return false;
        String expected = DLocalSignatures.hmacHex(
                props.xLogin() + xDate + (rawBody == null ? "" : rawBody), props.secretKey());
        String presented = authorization.contains("Signature:")
                ? authorization.substring(authorization.indexOf("Signature:") + "Signature:".length()).trim()
                : authorization.trim();
        return constantTimeEquals(expected, presented);
    }

    private static String normalize(String dlocalStatus) {
        return switch (dlocalStatus.toUpperCase(Locale.ROOT)) {
            case "PAID", "AUTHORIZED" -> "PAID";
            case "REJECTED", "CANCELLED", "EXPIRED" -> "CANCELLED";
            default -> "PENDING";
        };
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}

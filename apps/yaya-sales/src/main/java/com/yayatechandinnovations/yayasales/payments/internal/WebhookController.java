package com.yayatechandinnovations.yayasales.payments.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * §10.3 — PSP webhooks: signature-verified, idempotent (deduped on the
 * provider's event id), and the ONLY thing that flips a PSP order → PAID.
 * The HTTP layer is open (SecurityConfig permits {@code /v1/sales/webhooks/**})
 * because each handler verifies its provider's signature itself.
 *
 * <p>S3 ships the sandbox provider (HMAC-SHA256 over the raw body in
 * {@code X-Sandbox-Signature}); Stripe/PayPal/dLocal verifiers port from
 * {@code commerce/payment} at S4 behind the same shape.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final DefaultTenderService tenderService;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;
    private final byte[] sandboxSecret;

    public WebhookController(DefaultTenderService tenderService,
                             IdempotencyStore idempotency,
                             ObjectMapper json,
                             @Value("${yaya.sales.payments.sandbox.webhook-secret:}") String sandboxSecret) {
        this.tenderService = tenderService;
        this.idempotency = idempotency;
        this.json = json;
        this.sandboxSecret = sandboxSecret == null || sandboxSecret.isBlank()
                ? null : sandboxSecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/v1/sales/webhooks/sandbox")
    public ResponseEntity<Map<String, String>> sandbox(
            @RequestHeader(value = "X-Sandbox-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (sandboxSecret == null) {
            return ResponseEntity.status(503).body(Map.of("error", "sandbox webhook not configured"));
        }
        if (signature == null || !constantTimeEquals(hmac(sandboxSecret, rawBody), signature)) {
            // Reject BEFORE parsing: an unsigned body is untrusted input.
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }

        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed body"));
        }
        String eventId = text(event, "eventId");
        String type = text(event, "type");
        String orderId = text(event, "orderId");
        String storeId = text(event, "storeId");
        String pspRef = text(event, "pspRef");
        if (eventId == null || orderId == null || storeId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "eventId, orderId, storeId required"));
        }

        // Idempotent on the provider's event id — a replay acknowledges without re-settling.
        String scopeKey = "webhook:sandbox:" + eventId;
        if (idempotency.find(new StoreId(storeId), scopeKey).isPresent()) {
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }
        idempotency.put(new StoreId(storeId), scopeKey, "sandbox", "{\"handled\":true}");

        if ("payment.captured".equals(type)) {
            tenderService.settlePspByOrder(new OrderId(orderId), pspRef);
        } else {
            log.info("sandbox webhook: ignoring event type {}", type);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
